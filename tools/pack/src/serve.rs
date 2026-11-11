//! A tiny static file server for testing the launcher against a local pack.

use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha512};

use crate::Result;

pub fn run(root: &Path, port: u16) -> Result<()> {
    let listener = TcpListener::bind(("127.0.0.1", port))?;
    println!("Serving {} at http://127.0.0.1:{port}/ (Ctrl+C stops it)", root.display());
    for stream in listener.incoming() {
        let stream = stream?;
        let root = root.to_path_buf();
        std::thread::spawn(move || {
            if let Err(error) = handle(stream, &root) {
                eprintln!("request failed: {error}");
            }
        });
    }
    Ok(())
}

fn handle(mut stream: TcpStream, root: &Path) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut request = String::new();
    reader.read_line(&mut request)?;
    let mut if_none_match = None;
    loop {
        let mut header = String::new();
        if reader.read_line(&mut header)? == 0 || header.trim().is_empty() {
            break;
        }
        if let Some((name, value)) = header.split_once(':') {
            if name.trim().eq_ignore_ascii_case("if-none-match") {
                if_none_match = Some(value.trim().to_string());
            }
        }
    }

    let mut parts = request.split_whitespace();
    let (method, target) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
    if method != "GET" {
        return respond(&mut stream, "405 Method Not Allowed", "text/plain", &[], None);
    }
    let Some(file) = resolve(root, target) else {
        return respond(&mut stream, "404 Not Found", "text/plain", b"not found", None);
    };
    let Ok(body) = fs::read(&file) else {
        return respond(&mut stream, "404 Not Found", "text/plain", b"not found", None);
    };
    let etag = format!("\"{}\"", &cuc_manifest::encode_hex(&Sha512::digest(&body))[..16]);
    println!("GET {target}");
    if if_none_match.as_deref() == Some(etag.as_str()) {
        return respond(&mut stream, "304 Not Modified", "text/plain", &[], Some(&etag));
    }
    let kind = match file.extension().and_then(|e| e.to_str()) {
        Some("json") => "application/json",
        Some("jar") => "application/java-archive",
        _ => "application/octet-stream",
    };
    respond(&mut stream, "200 OK", kind, &body, Some(&etag))
}

fn resolve(root: &Path, target: &str) -> Option<PathBuf> {
    let path = decode(target.split('?').next()?.trim_start_matches('/'))?;
    cuc_manifest::is_safe_relative_path(&path).then(|| root.join(path))
}

fn decode(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            out.push(u8::from_str_radix(text.get(i + 1..i + 3)?, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn respond(stream: &mut TcpStream, status: &str, kind: &str, body: &[u8], etag: Option<&str>) -> std::io::Result<()> {
    let etag = etag.map(|e| format!("ETag: {e}\r\n")).unwrap_or_default();
    write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Type: {kind}\r\nContent-Length: {}\r\n{etag}Cache-Control: no-cache\r\nConnection: close\r\n\r\n",
        body.len()
    )?;
    stream.write_all(body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_and_stays_inside_the_root() {
        let root = Path::new("out");
        assert_eq!(resolve(root, "/stable/files/cube-utility-1.7.0%2Bmc26.1-26.3.jar"), Some(root.join("stable/files/cube-utility-1.7.0+mc26.1-26.3.jar")));
        assert_eq!(resolve(root, "/../secret"), None);
        assert_eq!(resolve(root, "/stable/%2e%2e/x"), None);
    }
}

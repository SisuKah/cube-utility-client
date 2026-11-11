// Release builds are GUI apps: no extra console window on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod auth;
mod config;
mod error;
mod game;
mod launcher;
mod logs;
mod net;
mod paths;
mod sync;
mod system;
mod update;

use tauri::Manager;

fn main() {
    let app = tauri::Builder::default()
        // One launcher at a time: starting it again focuses the open window.
        // This plugin has to be registered first.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .manage(update::SelfUpdate::default())
        .setup(|app| {
            let root = app.path().data_dir()?.join(config::DATA_FOLDER);
            std::fs::create_dir_all(&root)?;
            // Use the folder's real path, so Java and Fabric see exactly the paths we pass them.
            let paths = paths::Paths::new(paths::real_path(&root));
            paths.ensure()?;
            app.manage(logs::init(&paths.logs()));
            let version = app.package_info().version.to_string();
            tracing::info!("Cube Utility Client {version} starting; data in {}", paths.root().display());
            match config::manifest_url(config::DEFAULT_CHANNEL) {
                Some(url) => tracing::info!("pack: {url}"),
                None => tracing::info!("no pack server set yet, so no mods are synced"),
            }
            let net = net::Net::new()?;
            app.manage(auth::Auth::new(paths.clone(), net.clone()));
            app.manage(launcher::Launcher::new(paths, net, version));
            auth::start(app.handle().clone());
            launcher::start(app.handle().clone());
            update::start(app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            system::system_info,
            launcher::launcher_state,
            launcher::pack_info,
            launcher::play,
            launcher::repair,
            launcher::set_mod_enabled,
            launcher::sync_now,
            update::update_ready,
            update::restart_to_update,
            auth::account_state,
            auth::sign_in,
            auth::cancel_sign_in,
            auth::sign_out,
        ])
        .build(tauri::generate_context!())
        .expect("failed to start Cube Utility Client");

    app.run(|app, event| {
        if let tauri::RunEvent::Exit = event {
            update::install_on_quit(app);
        }
    });
}

//! The tray icon (spec section 5): Play CubeCraft and Quit. A left click shows the launcher,
//! which is handy while it hides during play.

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};

use crate::launcher;

pub fn create(app: &tauri::App) -> tauri::Result<()> {
    let play = MenuItem::with_id(app, "play", "Play CubeCraft", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&play, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main")
        .tooltip("Cube Utility Client")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "play" => launcher::play_from_tray(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                launcher::show_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

//! Installing and launching Minecraft with Fabric, following Mojang's version file format
//! (the "client.json" page on minecraft.wiki).

pub mod args;
pub mod install;
pub mod java;
pub mod launch;
pub mod rules;
pub mod version;

pub use install::{resolve, GamePlan};

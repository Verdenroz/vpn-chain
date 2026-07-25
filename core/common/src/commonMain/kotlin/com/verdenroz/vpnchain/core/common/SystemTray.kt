package com.verdenroz.vpnchain.core.common

/**
 * Whether this desktop can actually show a tray icon.
 *
 * Not a given on Linux: AWT speaks the older XEmbed tray protocol, while most
 * Wayland bars (waybar, ags, Hyprland setups generally) implement
 * StatusNotifierItem over DBus instead, and nothing bridges the two. Anything
 * that hides the window into the tray has to check this first, or the app
 * becomes a running process with no way to reach it.
 */
expect val systemTrayAvailable: Boolean

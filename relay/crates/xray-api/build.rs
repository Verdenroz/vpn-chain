fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut config = tonic_prost_build::Config::new();
    config.protoc_executable(protoc_bin_vendored::protoc_bin_path()?);
    config.enable_type_names();
    tonic_prost_build::configure()
        .build_server(true)
        .server_mod_attribute(".", "#[cfg(test)]")
        .generate_default_stubs(true)
        .include_file("xray.rs")
        .compile_with_config(
            config,
            &[
                "proto/app/proxyman/command/command.proto",
                "proto/app/stats/command/command.proto",
                "proto/proxy/vless/account.proto",
            ],
            &["proto"],
        )?;
    Ok(())
}

package com.verdenroz.vpnchain.core.update.di

import org.koin.core.module.Module

/** Binds the repository, the platform [com.verdenroz.vpnchain.core.update.AppInstaller] and the running updater. */
expect val updateModule: Module

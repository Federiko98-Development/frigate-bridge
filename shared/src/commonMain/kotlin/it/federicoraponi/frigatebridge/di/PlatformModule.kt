package it.federicoraponi.frigatebridge.di

import org.koin.core.module.Module

/** Provides the platform parts: HTTP client, secret storage and the preferences file. */
internal expect val platformModule: Module

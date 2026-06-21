package com.freeconnect.bedrock

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — entry point for Hilt dependency injection.
 * Hilt generates the component graph from this class.
 */
@HiltAndroidApp
class FreeConnectApplication : Application()

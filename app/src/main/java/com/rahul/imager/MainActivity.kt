package com.rahul.imager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahul.imager.data.AppSettings
import com.rahul.imager.data.AppSettingsStore
import com.rahul.imager.ui.ImagerApp
import com.rahul.imager.ui.theme.ImagerTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the app-wide settings the theme depends on.
 *
 * Separate from every screen view model because the theme has to be resolved once, above the
 * navigation graph, before any screen composes.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    appSettingsStore: AppSettingsStore,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            appSettingsStore.settings.collect { _settings.value = it }
        }
    }
}

/**
 * The single activity.
 *
 * Edge to edge, and no orientation lock anywhere: the layout adapts through
 * [calculateWindowSizeClass] rather than by being pinned to one shape.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val settings by appViewModel.settings.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)

            ImagerTheme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImagerApp(windowSizeClass = windowSizeClass)
                }
            }
        }
    }
}

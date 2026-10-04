package com.stream4k60.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.stream4k60.app.ui.main.MainStudioScreen
import com.stream4k60.app.ui.profiles.ProfileManagerScreen
import com.stream4k60.app.ui.settings.SettingsScreen

@Composable
fun AppNavHost(){
    val nav=rememberNavController()
    NavHost(nav, startDestination=NavRoutes.MainStudio.route){
        composable(NavRoutes.MainStudio.route){
            // Settings and Profiles open over the studio. Navigating away disposed the studio screen, which stopped every
            // source and the USB camera even while live; OBS keeps everything running behind its windows.
            var settingsCategory by rememberSaveable { mutableStateOf<String?>(null) }
            var profiles by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = settingsCategory != null || profiles) { settingsCategory = null; profiles = false }
            Box(Modifier.fillMaxSize()) {
                MainStudioScreen({ category -> settingsCategory = category }, { profiles = true })
                settingsCategory?.let { category ->
                    Surface(Modifier.fillMaxSize()) { SettingsScreen(onNavigateBack = { settingsCategory = null }, initialCategory = category) }
                }
                if (profiles) Surface(Modifier.fillMaxSize()) { ProfileManagerScreen(onClose = { profiles = false }) }
            }
        }
        composable(NavRoutes.Settings.route){SettingsScreen(onNavigateBack={nav.popBackStack()})}
        composable(NavRoutes.SettingsCategory.route, arguments=listOf(navArgument("category"){type=NavType.StringType})){entry->
            SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory=entry.arguments?.getString("category") ?: "General")
        }
        composable(NavRoutes.ProfileManager.route){ProfileManagerScreen(onClose={nav.popBackStack()})}
        composable(NavRoutes.SettingsGeneral.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="General")}
        composable(NavRoutes.SettingsStream.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Stream")}
        composable(NavRoutes.SettingsOutput.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Output")}
        composable(NavRoutes.SettingsAudio.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Audio")}
        composable(NavRoutes.SettingsVideo.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Video")}
        composable(NavRoutes.SettingsHotkeys.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Hotkeys")}
        composable(NavRoutes.SettingsAdvanced.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Advanced")}
        composable(NavRoutes.SettingsAccessibility.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Accessibility")}
    }
}

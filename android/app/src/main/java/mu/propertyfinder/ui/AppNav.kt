package mu.propertyfinder.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.StateFlow
import mu.propertyfinder.R
import mu.propertyfinder.SharedContent
import mu.propertyfinder.ui.detail.DetailScreen
import mu.propertyfinder.ui.explore.ExploreScreen
import mu.propertyfinder.ui.post.AddPostScreen
import mu.propertyfinder.ui.saved.SavedScreen

private data class Tab(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("explore", R.string.nav_explore, Icons.Filled.Place),
    Tab("saved", R.string.nav_saved, Icons.Filled.FavoriteBorder),
    Tab("post", R.string.nav_add_post, Icons.Filled.Add),
)

@Composable
fun AppNav(
    sharedContent: StateFlow<SharedContent?>,
    onSharedConsumed: () -> Unit,
    openProperty: StateFlow<String?>,
    onOpenConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val shared by sharedContent.collectAsState()

    fun go(target: String) = nav.navigate(target) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    LaunchedEffect(shared) { if (shared != null) go("post") }
    val alertTarget by openProperty.collectAsState()
    LaunchedEffect(alertTarget) {
        alertTarget?.let { nav.navigate("property/$it"); onOpenConsumed() }
    }

    Scaffold(
        bottomBar = {
            if (route?.startsWith("property") != true) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { go(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "explore", modifier = Modifier.padding(padding)) {
            val open: (String) -> Unit = { id -> nav.navigate("property/$id") }
            composable("explore") { ExploreScreen(onOpen = open) }
            composable("saved") { SavedScreen(onOpen = open, onApplySearch = { go("explore") }) }
            composable("post") {
                AddPostScreen(shared = shared, onSharedConsumed = onSharedConsumed, onPublished = open)
            }
            composable("property/{id}") { backStack ->
                DetailScreen(id = backStack.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
            }
        }
    }
}

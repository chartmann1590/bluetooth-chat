package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.charles.meshtalk.app.ads.AdsManager
import com.charles.meshtalk.app.ads.BannerAdView
import com.charles.meshtalk.app.billing.BillingRepositoryProvider
import com.charles.meshtalk.app.billing.EntitlementGate
import com.charles.meshtalk.app.billing.findActivity
import com.charles.meshtalk.app.billing.isUnlocked
import com.charles.meshtalk.app.notifications.VoiceNotifier
import com.charles.meshtalk.app.repository.MeshRepository

private const val ROUTE_PUBLIC = "public"
private const val ROUTE_MESSAGES = "messages"
private const val ROUTE_NEARBY = "nearby"
private const val ROUTE_WALKIE_TALKIE = "walkie_talkie"
private const val ROUTE_AI_CHAT = "ai_chat"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DM_THREAD = "dm/{peerKey}"
private const val ROUTE_FIND = "find/{peerKey}"
private const val ROUTE_FIND_ALL = "find_all"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshTalkApp(
    repository: MeshRepository,
    pendingDmPeerKey: String? = null,
    pendingWalkieTalkieTarget: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onWalkieTalkieDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val findFeatureEnabled by repository.findFeatureEnabled.collectAsState()
    val context = LocalContext.current
    val billingRepository = remember { BillingRepositoryProvider.create(context) }
    val entitlementState by billingRepository.entitlementState.collectAsState()
    val adsEnabled = !entitlementState.isUnlocked()

    LaunchedEffect(Unit) {
        AdsManager.init(context)
    }
    LaunchedEffect(adsEnabled) {
        if (adsEnabled) AdsManager.loadInterstitialIfNeeded(context, billingRepository)
    }

    LaunchedEffect(pendingDmPeerKey) {
        if (pendingDmPeerKey != null) {
            navController.navigate("dm/$pendingDmPeerKey")
            onDeepLinkConsumed()
        }
    }

    // Held separately from `pendingWalkieTalkieTarget` (which the parent Activity clears right
    // after this effect runs) so the ROUTE_WALKIE_TALKIE composable below still has a value to
    // read for its initial target once navigation actually completes.
    var walkieTalkieInitialTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingWalkieTalkieTarget) {
        if (pendingWalkieTalkieTarget != null) {
            walkieTalkieInitialTarget = pendingWalkieTalkieTarget
            navController.navigate(ROUTE_WALKIE_TALKIE) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onWalkieTalkieDeepLinkConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            Column {
                // Only ever shown while the walkie-talkie entitlement is locked — purchasing or
                // subscribing (the app's one paid product) is treated as "ad-free/pro" too, so this
                // disappears the moment entitlementState reflects an unlocked state.
                if (adsEnabled) BannerAdView()

                NavigationBar {
                    buildList {
                        add(Triple(ROUTE_PUBLIC, "Public", Icons.Filled.Public))
                        add(Triple(ROUTE_MESSAGES, "Messages", Icons.Filled.Forum))
                        add(Triple(ROUTE_NEARBY, "Nearby", Icons.Filled.People))
                        add(Triple(ROUTE_WALKIE_TALKIE, "Talk", Icons.Filled.Mic))
                        if (findFeatureEnabled) add(Triple(ROUTE_FIND_ALL, "Find", Icons.Filled.Explore))
                        add(Triple(ROUTE_AI_CHAT, "AI", Icons.Filled.Psychology))
                        add(Triple(ROUTE_SETTINGS, "Settings", Icons.Filled.Settings))
                    }.forEach { (route, label, icon) ->
                        NavigationBarItem(
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                            onClick = {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                                if (adsEnabled) {
                                    context.findActivity()?.let { AdsManager.maybeShowInterstitial(it, billingRepository) }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_PUBLIC,
            modifier = Modifier.padding(padding)
        ) {
            composable(ROUTE_PUBLIC) { PublicFeedScreen(repository) }
            composable(ROUTE_MESSAGES) { MessagesListScreen(repository, onOpenThread = { peerKey ->
                navController.navigate("dm/$peerKey")
            }) }
            composable(ROUTE_NEARBY) { NearbyScreen(
                repository,
                onMessagePeer = { peerKey -> navController.navigate("dm/$peerKey") },
                onFindPeer = { peerKey -> navController.navigate("find/$peerKey") }
            ) }
            composable(ROUTE_WALKIE_TALKIE) {
                val initialPeerKeyHex = walkieTalkieInitialTarget?.takeIf { it != VoiceNotifier.TARGET_PUBLIC }
                EntitlementGate(billingRepository) { WalkieTalkieScreen(repository, initialPeerKeyHex) }
            }
            composable(ROUTE_AI_CHAT) { AiChatScreen() }
            composable(ROUTE_FIND_ALL) { FindAllScreen(repository, onOpenPeer = { peerKey ->
                navController.navigate("find/$peerKey")
            }) }
            composable(ROUTE_SETTINGS) { SettingsScreen(repository) }
            composable(ROUTE_DM_THREAD) { backStackEntry ->
                val peerKey = backStackEntry.arguments?.getString("peerKey") ?: return@composable
                DmThreadScreen(repository, peerKey)
            }
            composable(ROUTE_FIND) { backStackEntry ->
                val peerKey = backStackEntry.arguments?.getString("peerKey") ?: return@composable
                FindScreen(repository, peerKey)
            }
        }
    }
}

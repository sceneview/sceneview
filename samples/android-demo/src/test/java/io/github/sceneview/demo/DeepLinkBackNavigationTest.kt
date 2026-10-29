package io.github.sceneview.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkBackNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backDuringColdDeepLinkLoad_returnsHomeAndCancelsDestinationWork() {
        var pendingId by mutableStateOf<String?>("model-viewer")
        var destinationDisposed = false
        var loadCancelled = false

        composeRule.setContent {
            val navController = rememberNavController()
            PendingDemoNavigation(navController, pendingId) { pendingId = null }
            NavHost(navController = navController, startDestination = "list") {
                composable("list") { Text("Home") }
                composable("demo/{id}") {
                    DisposableEffect(Unit) {
                        onDispose { destinationDisposed = true }
                    }
                    LaunchedEffect(Unit) {
                        try {
                            awaitCancellation()
                        } finally {
                            loadCancelled = true
                        }
                    }
                    Column {
                        Text("Still loading…")
                        Button(onClick = { navController.popBackStack() }) {
                            Text("Navigate back")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Still loading…").assertIsDisplayed()
        composeRule.onNodeWithText("Navigate back").performClick()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Still loading…").assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue("the deep-link destination must leave composition", destinationDisposed)
            assertTrue("destination-scoped loading work must be cancelled", loadCancelled)
        }
    }

    /** #4502: a link received while a demo is open replaces it; Back shows Home. */
    @Test
    fun warmDeepLinkOverAnOpenDemo_replacesItAndBackReturnsHome() {
        var pendingId by mutableStateOf<String?>("model-viewer")

        composeRule.setContent {
            val navController = rememberNavController()
            PendingDemoNavigation(navController, pendingId) { pendingId = null }
            WarmLinkNavHost(navController)
        }
        composeRule.onNodeWithText("Demo model-viewer #1").assertIsDisplayed()

        pendingId = "lighting"
        composeRule.onNodeWithText("Demo lighting #2").assertIsDisplayed()

        composeRule.onNodeWithText("Navigate back").performClick()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Demo model-viewer #1").assertDoesNotExist()
    }

    /** #4502: a link to the demo already open restarts it, so its `?tab=` is read again. */
    @Test
    fun warmDeepLinkToTheOpenDemo_restartsItAndBackReturnsHome() {
        var pendingId by mutableStateOf<String?>("lighting")

        composeRule.setContent {
            val navController = rememberNavController()
            PendingDemoNavigation(navController, pendingId) { pendingId = null }
            WarmLinkNavHost(navController)
        }
        composeRule.onNodeWithText("Demo lighting #1").assertIsDisplayed()

        pendingId = "lighting"
        composeRule.onNodeWithText("Demo lighting #2").assertIsDisplayed()

        composeRule.onNodeWithText("Navigate back").performClick()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }

    /** Each demo destination shows its id and the order in which it was created. */
    @Composable
    private fun WarmLinkNavHost(navController: NavHostController) {
        val created = remember { intArrayOf(0) }
        NavHost(navController = navController, startDestination = "list") {
            composable("list") { Text("Home") }
            composable("demo/{id}") { entry ->
                val serial = rememberSaveable { ++created[0] }
                Column {
                    Text("Demo ${entry.arguments?.getString("id")} #$serial")
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Navigate back")
                    }
                }
            }
        }
    }
}

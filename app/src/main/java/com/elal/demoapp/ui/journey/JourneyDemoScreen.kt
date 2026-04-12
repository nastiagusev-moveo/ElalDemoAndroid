package com.elal.demoapp.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elal.demoapp.ui.theme.ElAlBlue
import com.elal.demoapp.ui.theme.ElAlDemoTheme
import com.elal.demoapp.ui.theme.Green
import com.elal.demoapp.ui.theme.Red

@Composable
fun JourneyDemoScreen(viewModel: JourneyViewModel = viewModel()) {
    val statusText by viewModel.statusText.collectAsState()
    val detailText by viewModel.detailText.collectAsState()
    val isActivityActive by viewModel.isActivityActive.collectAsState()
    val isSimulating by viewModel.isSimulating.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    JourneyDemoContent(
        statusText = statusText,
        detailText = detailText,
        isActivityActive = isActivityActive,
        isSimulating = isSimulating,
        errorMessage = errorMessage,
        onStartActivity = { viewModel.startActivity() },
        onNextStage = { viewModel.updateToNextState() },
        onEndActivity = { viewModel.endActivity() },
        onConnectionError = { viewModel.setConnectionError() },
        onGeneralError = { viewModel.setGeneralError() },
        onSimulateJourney = { viewModel.simulateJourney() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyDemoContent(
    statusText: String,
    detailText: String?,
    isActivityActive: Boolean,
    isSimulating: Boolean,
    errorMessage: String?,
    onStartActivity: () -> Unit,
    onNextStage: () -> Unit,
    onEndActivity: () -> Unit,
    onConnectionError: () -> Unit,
    onGeneralError: () -> Unit,
    onSimulateJourney: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journey") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ElAlBlue,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Status Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ElAlBlue)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Current Stage",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                if (detailText != null) {
                    Text(
                        text = detailText,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                }
            }

            // Control Buttons
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartActivity,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isActivityActive,
                    colors = ButtonDefaults.buttonColors(containerColor = Green)
                ) {
                    Text("Start Activity")
                }

                Button(
                    onClick = onNextStage,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isActivityActive,
                    colors = ButtonDefaults.buttonColors(containerColor = ElAlBlue)
                ) {
                    Text("Next Stage")
                }

                Button(
                    onClick = onEndActivity,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isActivityActive,
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("End Activity")
                }
            }

            // Error State Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onConnectionError,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("Conn. Error")
                }

                Button(
                    onClick = onGeneralError,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("Error")
                }
            }

            HorizontalDivider()

            // Simulate Button
            Button(
                onClick = onSimulateJourney,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isSimulating,
                colors = ButtonDefaults.buttonColors(containerColor = ElAlBlue)
            ) {
                Text(
                    text = "Simulate Journey",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // CircularProgressIndicator — shown while simulation is running
            if (isSimulating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = ElAlBlue
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simulating journey…", fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, locale = "iw")
@Composable
fun JourneyDemoContentIdlePreview() {
    ElAlDemoTheme {
        JourneyDemoContent(
            statusText = "Idle",
            detailText = null,
            isActivityActive = false,
            isSimulating = false,
            errorMessage = null,
            onStartActivity = {},
            onNextStage = {},
            onEndActivity = {},
            onConnectionError = {},
            onGeneralError = {},
            onSimulateJourney = {}
        )
    }
}

@Preview(showBackground = true, locale = "iw")
@Composable
fun JourneyDemoContentBoardingPreview() {
    ElAlDemoTheme {
        JourneyDemoContent(
            statusText = "Boarding",
            detailText = "Group 3 · Gate B14",
            isActivityActive = true,
            isSimulating = false,
            errorMessage = null,
            onStartActivity = {},
            onNextStage = {},
            onEndActivity = {},
            onConnectionError = {},
            onGeneralError = {},
            onSimulateJourney = {}
        )
    }
}

@Preview(showBackground = true, locale = "iw")
@Composable
fun JourneyDemoContentSimulatingPreview() {
    ElAlDemoTheme {
        JourneyDemoContent(
            statusText = "On The Way",
            detailText = "ETA 14:35",
            isActivityActive = true,
            isSimulating = true,
            errorMessage = null,
            onStartActivity = {},
            onNextStage = {},
            onEndActivity = {},
            onConnectionError = {},
            onGeneralError = {},
            onSimulateJourney = {}
        )
    }
}

@Preview(showBackground = true, locale = "iw")
@Composable
fun JourneyDemoContentErrorPreview() {
    ElAlDemoTheme {
        JourneyDemoContent(
            statusText = "Connection Error",
            detailText = null,
            isActivityActive = true,
            isSimulating = false,
            errorMessage = "You seem to be offline. Check your connection.",
            onStartActivity = {},
            onNextStage = {},
            onEndActivity = {},
            onConnectionError = {},
            onGeneralError = {},
            onSimulateJourney = {}
        )
    }
}

package com.mercuryworkshop.whisperandroid

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.mercuryworkshop.whisperandroid.ui.theme.WhisperandroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperandroidTheme(darkTheme = true) {
                WhisperActivity()
            }
        }
    }
}

@Composable
fun WhisperActivity() {
    var vpnUrl by remember { mutableStateOf("") }
    var dohUrl by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(context)
            connected = true
        } else {
            connected = false
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1C1F), Color(0xFF111214))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.wisp_foreground),
                    contentDescription = "wisp",
                    modifier = Modifier.size(80.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Whisper",
                    color = Color(0xFFE0E0E0),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

//            Row(
//                modifier = Modifier
//                    .fillMaxWidth(),
//                horizontalArrangement = Arrangement.spacedBy(10.dp)
//            ) {
//                Button(
//                    onClick = { /* TODO */ },
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color(0xFF2C2F33),
//                        contentColor = Color(0xFFE0E0E0)
//                    ),
//                    shape = RoundedCornerShape(12.dp),
//                    modifier = Modifier
//                        .weight(1.2f)
//                        .height(70.dp)
//                ) {
//                    Icon(
//                        painter = painterResource(id = R.drawable.qricon),
//                        contentDescription = "a qr code",
//                        modifier = Modifier.size(36.dp)
//                    )
//                    Spacer(modifier = Modifier.width(16.dp))
//                    Text(
//                        "Scan QR Code",
//                        fontSize = 20.sp
//                    )
//                }
//
//            }

            OutlinedTextField(
                value = vpnUrl,
                onValueChange = { vpnUrl = it },
                label = { Text("Wisp Server (default: Nebula)") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1F2124),
                    unfocusedContainerColor = Color(0xFF1F2124),
                    focusedLabelColor = Color(0xFFB0B3B8),
                    focusedIndicatorColor = Color(0xFF5A5F67),
                    unfocusedIndicatorColor = Color(0xFF3A3D42),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = dohUrl,
                onValueChange = { dohUrl = it },
                label = { Text("DoH URL (default: Cloudflare)") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1F2124),
                    unfocusedContainerColor = Color(0xFF1F2124),
                    focusedLabelColor = Color(0xFFB0B3B8),
                    focusedIndicatorColor = Color(0xFF5A5F67),
                    unfocusedIndicatorColor = Color(0xFF3A3D42),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    if (!connected) {
                        val intent = VpnService.prepare(context)
                        if (intent != null) {
                            vpnPermissionLauncher.launch(intent)
                        } else {
                            val startIntent = Intent(context, WhisperService::class.java).apply {
                                action = WhisperService.ACTION_CONNECT
                                putExtra(WhisperService.EXTRA_VPN_URL, vpnUrl)
                                putExtra(WhisperService.EXTRA_DOH_URL, dohUrl)
                            }
                            context.startService(startIntent)
                            connected = true
                        }
                    } else {
                        val stopIntent = Intent(context, WhisperService::class.java)
                        stopIntent.action = WhisperService.ACTION_DISCONNECT
                        context.startService(stopIntent)
                        connected = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2C2F33),
                    contentColor = Color(0xFFE0E0E0)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = if (connected) "Disconnect" else "Connect",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }



            Text(
                text = if (connected) "Connected" else "Disconnected",
                color = if (connected) Color(0xFF00FF00) else Color(0xFFEE4B2B),
                fontSize = 32.sp,
                fontWeight = if (connected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

fun startVpnService(context: android.content.Context) {
    val intent = Intent(context, WhisperService::class.java)
    context.startService(intent)
}

fun stopVpnService(context: android.content.Context) {
    val intent = Intent(context, WhisperService::class.java)
    intent.action = "com.mercuryworkshop.whisperandroid.STOP_VPN"
    context.startService(intent)
}

@Preview(showBackground = true)
@Composable
fun PreviewWhisperActivity() {
    WhisperandroidTheme(darkTheme = true) {
        WhisperActivity()
    }
}

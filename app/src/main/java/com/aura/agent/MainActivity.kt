
package com.aura.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AURAAgentScreen()
        }
    }
}

@Composable
fun AURAAgentScreen() {
    var status by remember {
        mutableStateOf("آماده برای شروع")
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "AURA-Agent",
                    fontSize = 32.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "دستیار هوشمند شخصی شما")

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = status)

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        status = "هسته اولیه برنامه فعال شد"
                    }
                ) {
                    Text(text = "راه‌اندازی")
                }
            }
        }
    }
}

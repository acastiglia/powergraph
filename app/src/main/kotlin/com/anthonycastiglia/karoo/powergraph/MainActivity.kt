package com.anthonycastiglia.karoo.powergraph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.anthonycastiglia.karoo.powergraph.screens.MainScreen
import com.anthonycastiglia.karoo.powergraph.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

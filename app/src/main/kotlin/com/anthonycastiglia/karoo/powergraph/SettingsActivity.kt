package com.anthonycastiglia.karoo.powergraph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.anthonycastiglia.karoo.powergraph.screens.SettingsScreen
import com.anthonycastiglia.karoo.powergraph.theme.AppTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                SettingsScreen()
            }
        }
    }
}

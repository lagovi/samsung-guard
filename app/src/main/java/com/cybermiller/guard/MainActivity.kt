package com.cybermiller.guard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val demoBtn = findViewById<Button>(R.id.demoBtn)

        startBtn.setOnClickListener {
            if (ensurePermissions()) startService()
        }
        stopBtn.setOnClickListener {
            stopService(Intent(this, GuardService::class.java))
            statusText.text = getString(R.string.status_stopped)
        }
        demoBtn.setOnClickListener {
            Thread { DemoSms.send(this) }.start()
        }
        val viberBtn = findViewById<Button>(R.id.viberBtn)
        viberBtn.setOnClickListener {
            ViberSender.openChat(this, BuildConfig.VIBER_OWNER_PHONE)
        }

        statusText.text = getString(R.string.status_stopped)
    }

    private fun startService() {
        val i = Intent(this, GuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        statusText.text = getString(R.string.status_running)
    }

    private fun ensurePermissions(): Boolean {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            false
        } else true
    }
}
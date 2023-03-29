package com.example.batterymanager_utility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor

class DataCollectionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    /* Pass the start of the CSV file as a parameter, so that we can update it, and return it when
    *  the work is done.
    *  Also try to see if you can pass the data to be collected, as well as the time interval of
    *  collection, as parameters.
    */

    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager
    private lateinit var broadcastReceiver: BroadcastReceiver
    private var lastKnownVoltage : Int = 0      // milivolts
    private var lastKnownLevel : Double = 0.0   // percentage
    private lateinit var stats : Array<String>  // stats are collected here


    override suspend fun doWork(): Result {
        // Start collecting data
        Log.i("BatteryMgr:doWork", "starting data collection")
        receiverSetup()
        return Result.success()
    }

    private fun receiverSetup() {
        batteryManager = this.applicationContext.getSystemService(BATTERY_SERVICE) as BatteryManager
        powerManager   = this.applicationContext.getSystemService(POWER_SERVICE  ) as PowerManager
        broadcastReceiver = BatteryManagerBroadcastReceiver { intent ->
            this.lastKnownVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            this.lastKnownLevel = (level * 100).toDouble() / scale
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        this.applicationContext.registerReceiver(broadcastReceiver, filter)
    }

    private fun getStats(): String {
        val timestamp = System.currentTimeMillis()

        // code from https://github.com/S2-group/batterydrainer/blob/master/app/src/main/java/nl/vu/cs/s2group/batterydrainer/LiveView.kt
        var currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) //Instantaneous battery current in microamperes
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)

        if(status == BatteryManager.BATTERY_STATUS_DISCHARGING) {   //some models report with inverted sign
            currentNow = -StrictMath.abs(currentNow)
        }

        val currentAverage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) //Average battery current in microamperes
        val watts = if(currentNow >= 0)  0.0 else (lastKnownVoltage.toDouble() / 1000) * (StrictMath.abs(
            currentNow
        ).toDouble()/1000/1000) //Only negative current means discharging

        val energy   = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) //Remaining energy in nanowatt-hours
        val capacity = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) //Remaining battery capacity in microampere-hours
        val capacityPercentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) //Remaining battery capacity as an integer percentage of total capacity

        /*
         * currentAverage always reports 0
         * energy         always reports 0
         * capacityPercentage == lastKnownLevel
         * Usable metrics: currentNow, watts, capacity
         */

        val estimatedLifeTime =
            StrictMath.abs((capacity.toDouble() / 1000) / (currentNow.toDouble() / 1000))
        val hours = floor(estimatedLifeTime)
        val minutes = ((estimatedLifeTime - hours)*60)

        return "$timestamp,$currentNow,$status,$currentAverage,$lastKnownVoltage,$watts,$energy," +
                "$capacity,$capacityPercentage,$hours,$minutes"
    }

    private fun writeToFile(file: File) {
        val batteryManager = this.applicationContext
                            .getSystemService(ComponentActivity.BATTERY_SERVICE) as BatteryManager
        while (true) {
            receiverSetup()

            Thread.sleep(1000)
            val stats = getStats()

            Log.i("BatteryMgr:writeToFile", "writing $stats")
            FileOutputStream(file, true).use {
                it.write("$stats\n".toByteArray())
            }
        }
    }

    private class BatteryManagerBroadcastReceiver(
        private val onReceiveIntent: (Intent) -> Unit,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReceiveIntent(intent)
        }
    }
}

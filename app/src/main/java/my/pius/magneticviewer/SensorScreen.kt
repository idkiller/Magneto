package my.pius.magneticviewer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.rotationMatrix
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

// Use a relative time offset (in seconds) for the x-axis
data class SensorDataPoint(val timeOffset: Float, val x: Float, val y: Float, val z: Float)

@Composable
fun SensorScreen() {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    var isRunning by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(0L) }

    val magneticFieldData = remember { mutableStateListOf<SensorDataPoint>() }
    val globalMagneticFieldDataGame = remember { mutableStateListOf<SensorDataPoint>() }
    val globalMagneticFieldDataRotation = remember { mutableStateListOf<SensorDataPoint>() }
    val globalMagneticFieldDataTilt = remember { mutableStateListOf<SensorDataPoint>() }
    val globalMagneticFieldDataRotation2 = remember { mutableStateListOf<SensorDataPoint>() }

    var gameRotationAccuracy by remember { mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    var rotationVectorAccuracy by remember { mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    var accelerometerAccuracy by remember { mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    var magnetometerAccuracy by remember { mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }

    fun accuracyColor(accuracy: Int): Color = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Color(0xFF4CAF50)
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Color(0xFFFFC107)
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> Color(0xFFFF9800)
        SensorManager.SENSOR_STATUS_UNRELIABLE -> Color(0xFFF44336)
        else -> Color.Gray
    }

    @Composable
    fun AccuracyIndicator(label: String, accuracy: Int, size: Dp = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(accuracyColor(accuracy), CircleShape)
            )
            Text(label, fontSize = 12.sp)
        }
    }

    fun rotateByQuaternion(vec: FloatArray, quat: FloatArray): FloatArray {
        val qw = quat[0]; val qx = quat[1]; val qy = quat[2]; val qz = quat[3]
        val vx = vec[0]; val vy = vec[1]; val vz = vec[2]

        val ix = qw * vx + qy * vz - qz * vy
        val iy = qw * vy + qz * vx - qx * vz
        val iz = qw * vz + qx * vy - qy * vx
        val iw = -qx * vx - qy * vy - qz * vz

        return floatArrayOf(
            ix * qw + iw * -qx + iy * -qz - iz * -qy,
            iy * qw + iw * -qy + iz * -qx - ix * -qz,
            iz * qw + iw * -qz + ix * -qy - iy * -qx
        )
    }

    // Use `remember` to create a single, stable listener instance
    val sensorListener = remember {
        object : SensorEventListener {
            // Initialize with identity quaternion [w, x, y, z] for no rotation
            private val rotationQuaternion = floatArrayOf(1f, 0f, 0f, 0f)
            private val rotationQuaternionFused = floatArrayOf(1f, 0f, 0f, 0f)
            private val rotationMatrix = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            private val gravity = FloatArray(3)
            private val geomagnetic = FloatArray(3)
            private var hasGravity = false
            private var hasMagnetic = false

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || !isRunning) return

                when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getQuaternionFromVector(rotationQuaternion, event.values)
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getQuaternionFromVector(rotationQuaternionFused, event.values)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        // Gravity vector is used to tilt-compensate the magnetometer with getRotationMatrix
                        System.arraycopy(event.values, 0, gravity, 0, gravity.size)
                        hasGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        // Use a relative timestamp in seconds for the chart's X-axis
                        val timeOffset = (System.currentTimeMillis() - startTime).toFloat() / 1000f
                        val magneticValues = event.values.clone()

                        System.arraycopy(magneticValues, 0, geomagnetic, 0, geomagnetic.size)
                        hasMagnetic = true

                        magneticFieldData.add(SensorDataPoint(timeOffset, magneticValues[0], magneticValues[1], magneticValues[2]))
                        while (magneticFieldData.isNotEmpty() && magneticFieldData.first().timeOffset < timeOffset - 3f) {
                            magneticFieldData.removeAt(0)
                        }

                        val globalMagneticFieldGame = rotateByQuaternion(magneticValues, rotationQuaternion)
                        globalMagneticFieldDataGame.add(
                            SensorDataPoint(timeOffset, globalMagneticFieldGame[0], globalMagneticFieldGame[1], globalMagneticFieldGame[2])
                        )
                        while (globalMagneticFieldDataGame.isNotEmpty() && globalMagneticFieldDataGame.first().timeOffset < timeOffset - 3f) {
                            globalMagneticFieldDataGame.removeAt(0)
                        }

                        val globalMagneticFieldRotation = rotateByQuaternion(magneticValues, rotationQuaternionFused)
                        globalMagneticFieldDataRotation.add(
                            SensorDataPoint(
                                timeOffset,
                                globalMagneticFieldRotation[0],
                                globalMagneticFieldRotation[1],
                                globalMagneticFieldRotation[2]
                            )
                        )
                        while (globalMagneticFieldDataRotation.isNotEmpty() && globalMagneticFieldDataRotation.first().timeOffset < timeOffset - 3f) {
                            globalMagneticFieldDataRotation.removeAt(0)
                        }

                        if (hasGravity && hasMagnetic) {
                            val rotationMatrix = FloatArray(9)
                            val inclination = FloatArray(9)
                            if (SensorManager.getRotationMatrix(rotationMatrix, inclination, gravity, geomagnetic)) {
                                // Transform device magnetic vector into world (east-north-up) coordinates
                                val globalX = rotationMatrix[0] * magneticValues[0] + rotationMatrix[1] * magneticValues[1] + rotationMatrix[2] * magneticValues[2]
                                val globalY = rotationMatrix[3] * magneticValues[0] + rotationMatrix[4] * magneticValues[1] + rotationMatrix[5] * magneticValues[2]
                                val globalZ = rotationMatrix[6] * magneticValues[0] + rotationMatrix[7] * magneticValues[1] + rotationMatrix[8] * magneticValues[2]

                                globalMagneticFieldDataTilt.add(SensorDataPoint(timeOffset, globalX, globalY, globalZ))
                                while (globalMagneticFieldDataTilt.isNotEmpty() && globalMagneticFieldDataTilt.first().timeOffset < timeOffset - 3f) {
                                    globalMagneticFieldDataTilt.removeAt(0)
                                }
                            }
                        }

                        globalMagneticFieldDataRotation2.add(SensorDataPoint(
                            timeOffset,
                            rotationMatrix[0] * magneticValues[0] + rotationMatrix[1] * magneticValues[1] + rotationMatrix[2] * magneticValues[2],
                            rotationMatrix[3] * magneticValues[0] + rotationMatrix[4] * magneticValues[1] + rotationMatrix[5] * magneticValues[2],
                            rotationMatrix[6] * magneticValues[0] + rotationMatrix[7] * magneticValues[1] + rotationMatrix[8] * magneticValues[2]
                        ))
                        while (globalMagneticFieldDataRotation2.isNotEmpty() && globalMagneticFieldDataRotation2.first().timeOffset < timeOffset - 3f) {
                            globalMagneticFieldDataRotation2.removeAt(0)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                when (sensor?.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> gameRotationAccuracy = accuracy
                    Sensor.TYPE_ROTATION_VECTOR -> rotationVectorAccuracy = accuracy
                    Sensor.TYPE_ACCELEROMETER -> accelerometerAccuracy = accuracy
                    Sensor.TYPE_MAGNETIC_FIELD -> magnetometerAccuracy = accuracy
                }
            }
        }
    }

    DisposableEffect(isRunning) {
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rotationVectorFusedSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (isRunning) {
            // Set start time and clear previous data
            startTime = System.currentTimeMillis()
            magneticFieldData.clear()
            globalMagneticFieldDataGame.clear()
            globalMagneticFieldDataRotation.clear()
            globalMagneticFieldDataTilt.clear()

            sensorManager.registerListener(sensorListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(sensorListener, rotationVectorFusedSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(sensorListener, accelerometerSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(sensorListener, magneticFieldSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            // This is called when the effect leaves the composition or `isRunning` changes.
            // It ensures the listener is always unregistered.
            sensorManager.unregisterListener(sensorListener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sensor accuracy", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccuracyIndicator("Game Rotation", gameRotationAccuracy)
                AccuracyIndicator("Rotation Vector", rotationVectorAccuracy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccuracyIndicator("Accelerometer", accelerometerAccuracy)
                AccuracyIndicator("Magnetometer", magnetometerAccuracy)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChartCard(
                title = "Device Magnetic Field (X, Y, Z)",
                data = magneticFieldData.toList(),
                modifier = Modifier.weight(1f)
            )
            ChartCard(
                title = "Global Magnetic Field via Game Rotation Vector (X, Y, Z)",
                data = globalMagneticFieldDataGame.toList(),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChartCard(
                title = "Global Magnetic Field via TYPE_ROTATION_VECTOR (X, Y, Z)",
                data = globalMagneticFieldDataRotation.toList(),
                modifier = Modifier.weight(1f)
            )
            ChartCard(
                title = "Global Magnetic Field via Accelerometer + getRotationMatrix (X, Y, Z)",
                data = globalMagneticFieldDataTilt.toList(),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChartCard(
                title = "Global Magnetic Field via TYPE_ROTATION_VECTOR + getRotationMatrixFromVector",
                data = globalMagneticFieldDataRotation2.toList(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = { isRunning = true }) {
                Text("Start")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = { isRunning = false }) {
                Text("Stop")
            }
        }
    }
}

@Composable
fun Chart(data: List<SensorDataPoint>, modifier: Modifier = Modifier) {
    // The `data` list is now guaranteed to trigger recomposition correctly.
    val entriesX = data.map { Entry(it.timeOffset, it.x) }
    val entriesY = data.map { Entry(it.timeOffset, it.y) }
    val entriesZ = data.map { Entry(it.timeOffset, it.z) }

    val dataSetX = LineDataSet(entriesX, "X").apply {
        color = Color.Red.toArgb()
        setDrawCircles(false)
        valueTextColor = Color.Transparent.toArgb()
    }
    val dataSetY = LineDataSet(entriesY, "Y").apply {
        color = Color.Green.toArgb()
        setDrawCircles(false)
        valueTextColor = Color.Transparent.toArgb()
    }
    val dataSetZ = LineDataSet(entriesZ, "Z").apply {
        color = Color.Blue.toArgb()
        setDrawCircles(false)
        valueTextColor = Color.Transparent.toArgb()
    }

    val lineData = LineData(dataSetX, dataSetY, dataSetZ)

    AndroidView(
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                axisRight.isEnabled = false
                // Improve performance for real-time data
                setHardwareAccelerationEnabled(true)
            }
        },
        update = { chart ->
            chart.data = lineData
            chart.notifyDataSetChanged()
            chart.invalidate()
        },
        modifier = modifier
    )
}

@Composable
private fun ChartCard(title: String, data: List<SensorDataPoint>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, fontSize = 9.sp)
        Chart(
            data = data,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )
    }
}

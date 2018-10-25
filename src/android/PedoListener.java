/**
 * Pedometer bridge with Cordova, programmed by Dario Salvi </dariosalvi78@gmail.com>
 * Based on the accelerometer plugin: https://github.com/apache/cordova-plugin-device-motion
 * License: MIT
 */
package org.apache.cordova.pedometer;

import java.util.List;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import java.util.Timer;
import java.util.TimerTask;
import android.os.CountDownTimer;

import android.os.Handler;

/**
 * This class listens to the pedometer sensor
 */
public class PedoListener extends CordovaPlugin implements SensorEventListener {

    public static int STOPPED = 0;
    public static int STARTING = 1;
    public static int RUNNING = 2;
    public static int ERROR_FAILED_TO_START = 3;
    public static int ERROR_NO_SENSOR_FOUND = 4;

    private int status; // status of listener
    private float startsteps; // first value, to be substracted
    private long starttimestamp; // time stamp of when the measurement starts

    private Sensor mSensor; // Pedometer sensor returned by sensor manager

    private CallbackContext callbackContext; // Keeps track of the JS callback context.

    private Handler mainHandler = null;

    private final String TAG = "StepInAcceleration";
    private Context context;
    public SensorManager sensorManager;
    public static int CURRENT_SETP = 0;
    public boolean isAvailable = false;

    // 存放三轴数据
    final int valueNum = 5;
    // 用于存放计算阈值的波峰波谷差值
    float[] tempValue = new float[valueNum];
    int tempCount = 0;
    // 是否上升的标志位
    boolean isDirectionUp = false;
    // 持续上升次数
    int continueUpCount = 0;
    // 上一点的持续上升的次数，为了记录波峰的上升次数
    int continueUpFormerCount = 0;
    // 上一点的状态，上升还是下降
    boolean lastStatus = false;
    // 波峰值
    float peakOfWave = 0;
    // 波谷值
    float valleyOfWave = 0;
    // 此次波峰的时间
    long timeOfThisPeak = 0;
    // 上次波峰的时间
    long timeOfLastPeak = 0;
    // 当前的时间
    long timeOfNow = 0;
    // 当前传感器的值
    float gravityNew = 0;
    // 上次传感器的值
    float gravityOld = 0;
    // 动态阈值需要动态的数据，这个值用于这些动态数据的阈值
    final float initialValue = (float) 1.7;
    // 初始阈值
    float ThreadValue = (float) 2.0;

    // 初始范围
    float minValue = 11f;
    float maxValue = 19.6f;

    /**
     * 0-准备计时 1-计时中 2-正常计步中
     */
    private int CountTimeState = 0;
    public static int TEMP_STEP = 0;
    private int lastStep = -1;
    // 用x、y、z轴三个维度算出的平均值
    public static float average = 0;
    private Timer timer;
    // 倒计时3.5秒，3.5秒内不会显示计步，用于屏蔽细微波动
    private long duration = 2500;
    private TimeCount time;

    /**
     * Constructor
     */
    public PedoListener() {
        this.starttimestamp = 0;
        this.startsteps = 0;
        this.setStatus(PedoListener.STOPPED);
    }

    /**
     * Sets the context of the Command. This can then be used to do things like get
     * file paths associated with the Activity.
     *
     * @param cordova the context of the main Activity.
     * @param webView the associated CordovaWebView.
     */
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.sensorManager = (SensorManager) cordova.getActivity().getSystemService(Context.SENSOR_SERVICE);
    }

    /**
     * Executes the request.
     *
     * @param action          the action to execute.
     * @param args            the exec() arguments.
     * @param callbackContext the callback context used when calling back into
     *                        JavaScript.
     * @return whether the action was valid.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
        if (action.equals("startPedometerUpdates")) {
            if (this.status != PedoListener.RUNNING) {
                this.start();
            }
            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT, "");
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);
            return true;
        } else if (action.equals("stopPedometerUpdates")) {
            if (this.status == PedoListener.RUNNING) {
                this.stop();
            }
            this.win(null);
            return true;
        } else {
            // Unsupported action
            return false;
        }
    }

    /**
     * Called by the Broker when listener is to be shut down. Stop listener.
     */
    public void onDestroy() {
        this.stop();
    }

    /**
     * Start listening for pedometers sensor.
     */
    private void start() {
        // If already starting or running, then return
        if ((this.status == PedoListener.RUNNING) || (this.status == PedoListener.STARTING)) {
            return;
        }
        starttimestamp = System.currentTimeMillis();
        this.startsteps = 0;
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        this.setStatus(PedoListener.STARTING);
        Log.d("WEC", "SSSSSS");

    }

    /**
     * Stop listening to sensor.
     */
    private void stop() {
        if (this.status != PedoListener.STOPPED) {
            this.sensorManager.unregisterListener(this);
        }
        this.setStatus(PedoListener.STOPPED);
    }

    /**
     * Called when the accuracy of the sensor has changed.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // nothing to do here
        return;
    }

    /**
     * Sensor listener event.
     * 
     * @param event
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        Sensor sensor = event.sensor;
        synchronized (this) {
            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                calc_step(event);
            }
        }

        // if (event.sensor.getType() != Sensor.TYPE_STEP_DETECTOR) {
        // return;
        // }

        // // If not running, then just return
        // if (this.status == PedoListener.STOPPED) {
        // return;
        // }

        // this.setStatus(PedoListener.RUNNING);

        // float steps = event.values[0];

        // if(this.startsteps == 0)
        // this.startsteps = steps;
        // steps = steps - this.startsteps;
        // Log.d("WEC","st : "+steps);

        // this.win(this.getStepsJSON(steps));
    }

    /**
     * Called when the view navigates.
     */
    @Override
    public void onReset() {
        if (this.status == PedoListener.RUNNING) {
            this.stop();
        }
    }

    // Sends an error back to JS
    private void fail(int code, String message) {
        // Error object
        JSONObject errorObj = new JSONObject();
        try {
            errorObj.put("code", code);
            errorObj.put("message", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PluginResult err = new PluginResult(PluginResult.Status.ERROR, errorObj);
        err.setKeepCallback(true);
        callbackContext.sendPluginResult(err);
    }

    private void win(JSONObject message) {
        // Success return object
        PluginResult result;
        if (message != null)
            result = new PluginResult(PluginResult.Status.OK, message);
        else
            result = new PluginResult(PluginResult.Status.OK);

        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private void win(boolean success) {
        // Success return object
        PluginResult result;
        result = new PluginResult(PluginResult.Status.OK, success);

        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private void setStatus(int status) {
        this.status = status;
    }

    private void newSteps(int steps) {
        JSONObject r = new JSONObject();
        try {
            r.put("time", System.currentTimeMillis());
            r.put("steps", steps);
            this.win(r);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    synchronized private void calc_step(SensorEvent event) {
        average = (float) Math
                .sqrt(Math.pow(event.values[0], 2) + Math.pow(event.values[1], 2) + Math.pow(event.values[2], 2));
        detectorNewStep(average);
    }

    /*
     * 检测波峰 以下四个条件判断为波峰： 1.目前点为下降的趋势：isDirectionUp为false
     * 2.之前的点为上升的趋势：lastStatus为true 3.到波峰为止，持续上升大于等于2次 4.波峰值大于1.2g,小于2g 记录波谷值
     * 1.观察波形图，可以发现在出现步子的地方，波谷的下一个就是波峰，有比较明显的特征以及差值 2.所以要记录每次的波谷值，为了和下次的波峰做对比
     */
    public boolean DetectorPeak(float newValue, float oldValue) {
        lastStatus = isDirectionUp;
        if (newValue >= oldValue) {
            isDirectionUp = true;
            continueUpCount++;
        } else {
            continueUpFormerCount = continueUpCount;
            continueUpCount = 0;
            isDirectionUp = false;
        }

        // Log.v(TAG, "oldValue:" + oldValue);
        if (!isDirectionUp && lastStatus
                && (continueUpFormerCount >= 2 && (oldValue >= minValue && oldValue < maxValue))) {
            peakOfWave = oldValue;
            return true;
        } else if (!lastStatus && isDirectionUp) {
            valleyOfWave = oldValue;
            return false;
        } else {
            return false;
        }
    }

    public void detectorNewStep(float values) {
        if (gravityOld == 0) {
            gravityOld = values;
        } else {
            if (DetectorPeak(values, gravityOld)) {
                timeOfLastPeak = timeOfThisPeak;
                timeOfNow = System.currentTimeMillis();

                if (timeOfNow - timeOfLastPeak >= 200 && (peakOfWave - valleyOfWave >= ThreadValue)
                        && (timeOfNow - timeOfLastPeak) <= 2000) {
                    timeOfThisPeak = timeOfNow;
                    // 更新界面的处理，不涉及到算法
                    preStep();
                }
                if (timeOfNow - timeOfLastPeak >= 200 && (peakOfWave - valleyOfWave >= initialValue)) {
                    timeOfThisPeak = timeOfNow;
                    ThreadValue = Peak_Valley_Thread(peakOfWave - valleyOfWave);
                }
            }
        }
        gravityOld = values;
    }

    private void preStep() {
        if (CountTimeState == 0) {
            // 开启计时器
            time = new TimeCount(duration, 700);
            time.start();
            CountTimeState = 1;
            Log.v(TAG, "开启计时器");
        } else if (CountTimeState == 1) {
            TEMP_STEP++;
            newSteps(1);
            Log.v(TAG, "计步中 TEMP_STEP:" + TEMP_STEP);
        } else if (CountTimeState == 2) {
            CURRENT_SETP++;
            this.newSteps(1);
            // if (stepCallBack != null) {
            // stepCallBack.Step(CURRENT_SETP);
            // }
        }
    }

    /*
     * 阈值的计算 1.通过波峰波谷的差值计算阈值 2.记录4个值，存入tempValue[]数组中 3.在将数组传入函数averageValue中计算阈值
     */
    public float Peak_Valley_Thread(float value) {
        float tempThread = ThreadValue;
        if (tempCount < valueNum) {
            tempValue[tempCount] = value;
            tempCount++;
        } else {
            tempThread = averageValue(tempValue, valueNum);
            for (int i = 1; i < valueNum; i++) {
                tempValue[i - 1] = tempValue[i];
            }
            tempValue[valueNum - 1] = value;
        }
        return tempThread;

    }

    /*
     * 梯度化阈值 1.计算数组的均值 2.通过均值将阈值梯度化在一个范围里
     */
    public float averageValue(float value[], int n) {
        float ave = 0;
        for (int i = 0; i < n; i++) {
            ave += value[i];
        }
        ave = ave / valueNum;
        if (ave >= 8) {
            // Log.v(TAG, "超过8");
            ave = (float) 4.3;
        } else if (ave >= 7 && ave < 8) {
            // Log.v(TAG, "7-8");
            ave = (float) 3.3;
        } else if (ave >= 4 && ave < 7) {
            // Log.v(TAG, "4-7");
            ave = (float) 2.3;
        } else if (ave >= 3 && ave < 4) {
            // Log.v(TAG, "3-4");
            ave = (float) 2.0;
        } else {
            // Log.v(TAG, "else");
            ave = (float) 1.7;
        }
        return ave;
    }

    class TimeCount extends CountDownTimer {
        public TimeCount(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        @Override
        public void onFinish() {
            // 如果计时器正常结束，则开始计步
            time.cancel();
            CURRENT_SETP += TEMP_STEP;
            lastStep = -1;
            Log.v(TAG, "计时正常结束");

            timer = new Timer(true);
            TimerTask task = new TimerTask() {
                public void run() {
                    if (lastStep == CURRENT_SETP) {
                        timer.cancel();
                        CountTimeState = 0;
                        lastStep = -1;
                        TEMP_STEP = 0;
                        Log.v(TAG, "停止计步：" + CURRENT_SETP);
                    } else {
                        lastStep = CURRENT_SETP;
                    }
                }
            };
            timer.schedule(task, 0, 2000);
            CountTimeState = 2;
        }

        @Override
        public void onTick(long millisUntilFinished) {
            if (lastStep == TEMP_STEP) {
                Log.v(TAG, "onTick 计时停止:" + TEMP_STEP);
                time.cancel();
                CountTimeState = 0;
                lastStep = -1;
                TEMP_STEP = 0;
            } else {
                lastStep = TEMP_STEP;
            }
        }

    }
}

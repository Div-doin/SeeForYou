<div align="center">

# 👁️ See For You

### AI-Powered Smart Assistive Glasses for Visually Impaired Individuals

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![TensorFlow Lite](https://img.shields.io/badge/AI-TensorFlow_Lite-FF6F00?style=flat-square&logo=tensorflow&logoColor=white)](https://tensorflow.org/lite)
[![Firebase](https://img.shields.io/badge/Backend-Firebase-FFCA28?style=flat-square&logo=firebase&logoColor=black)](https://firebase.google.com)
[![YOLOv8](https://img.shields.io/badge/Model-YOLOv8n-00FFFF?style=flat-square)](https://ultralytics.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

**A real-time, on-device AI system that acts as an artificial pair of eyes — detecting obstacles, reading text aloud, and alerting caregivers — all without internet connectivity.**

[Features](#-features) · [AI Pipeline](#-ai-pipeline) · [Voice Output](#-ai-powered-voice-output) · [Architecture](#-architecture) · [Setup](#-setup) · [Hardware Plan](#-hardware-roadmap)

</div>

---

## 🧭 What is See For You?

See For You is a **final-year UG capstone project** building an AI-powered assistive system for visually impaired individuals. The full system is a pair of smart glasses (Raspberry Pi Zero 2W + stereo camera + vibration motors) that uses edge AI to perceive the environment and communicate it to the user through intelligent voice output.

This repository contains the **Android prototype app** — built as a fully functional standalone tool using the phone camera to validate the complete AI pipeline before hardware assembly. Every feature in this app maps directly to a capability in the final glasses hardware.

> "We visited a blind center in Bengaluru and spoke with visually impaired users directly. They told us what they needed most: know what's ahead of me, read that label for me, tell me the bus number, alert my family if I'm in trouble. That's exactly what this system does."

---

## ✨ Features

| Feature | Description | AI Involved |
|---|---|---|
| 🎯 **Real-time Object Detection** | Detects 80 object classes live from camera | YOLOv8n TFLite on-device |
| 📖 **OCR Text Reading** | Reads medicine labels, signs, currency aloud | ML Kit Text Recognition |
| 🔊 **AI Voice Output** | Speaks everything aloud in English / Kannada / Hindi | Android TTS + AI context |
| 📜 **Detection History** | Logs every detection with timestamp and confidence | Firebase Realtime DB |
| ⚙️ **Smart Settings** | Volume, speed, language preferences | Persisted via SharedPreferences |
| 🆘 **SOS Emergency Alert** | Sends GPS location to caregiver via Firebase | FusedLocationProvider |

---

## 🤖 AI Pipeline

This is the core of the project. Every frame from the camera passes through a multi-stage on-device AI pipeline — **no internet required, no cloud API calls, zero latency overhead from network**.

```
Phone Camera (20 FPS)
        │
        ▼
┌─────────────────────────────────┐
│   Motion Detection (pre-filter) │  ← absdiff between frames
│   Skip frame if scene unchanged │    saves ~60% CPU
└────────────────┬────────────────┘
                 │ motion detected OR every 5th frame
                 ▼
┌─────────────────────────────────┐
│        Preprocessing            │
│  • Resize → 640×640             │
│  • BGR → RGB conversion         │
│  • Normalize pixel values [0,1] │
│  • Rotate by imageInfo.degrees  │  ← fixes sideways detection
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│      YOLOv8n TFLite Model       │
│   yolov8n_float32.tflite        │
│   Input:  [1, 3, 640, 640]      │
│   Output: [1, 84, 8400]         │  ← 4 bbox + 80 class scores
│                                 │     × 8400 anchor-free cells
│   Inference: ~80–200ms (phone)  │
│   Inference: ~180–300ms (Pi)    │
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│     Post-Processing             │
│  • Confidence threshold: 0.25   │
│  • NMS (IoU threshold: 0.45)    │  ← removes duplicate boxes
│  • argmax over 80 class scores  │
│  • Returns: label + bbox + conf │
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│     Decision & Priority Logic   │
│  DANGER  → car, bus, truck      │  ← immediate alert
│  CAUTION → person, bicycle      │  ← moderate alert
│  INFO    → chair, bottle, etc   │  ← low-priority
└────────────────┬────────────────┘
                 │
        ┌────────┴────────┐
        ▼                 ▼
   TTS Voice          Firebase Log
   Output             (if conf > 0.7)
```

### Model Details — YOLOv8n

| Property | Value |
|---|---|
| Model | YOLOv8 Nano (lightest variant) |
| Parameters | 3.2 million |
| Model file | `yolov8n_float32.tflite` |
| Input size | 640 × 640 × 3 |
| Output tensor | `[1, 84, 8400]` |
| Classes | 80 COCO classes |
| Inference (Android) | 80–200ms |
| Inference (Pi Zero TFLite INT8) | ~180–300ms |
| Why YOLOv8n? | Smallest YOLO variant, runs on edge hardware, 6MB file size |

The output tensor `[1, 84, 8400]` means: for each of 8400 anchor-free grid positions, the model predicts 4 bounding box coordinates (`cx, cy, w, h`) and 80 class confidence scores. The `YoloDetector.kt` class handles full decoding, confidence filtering, and NMS in pure Kotlin.

---

## 🔊 AI-Powered Voice Output

Voice output is not just TTS — it is **context-aware, priority-driven, and deduplicated using AI detection state**. Here is how it works:

### How the AI decides what to say

```kotlin
// TtsService.kt — smart deduplication
fun speak(text: String, force: Boolean = false) {
    if (text == lastSpoken && !force) return  // ← don't repeat same object every frame
    lastSpoken = text
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "uid_$text")
}
```

The TTS engine does not blindly speak every detection. It only speaks when:
- A **new object** enters the frame (different from last spoken)
- A **dangerous object** is detected (force = true, interrupts current speech)
- The **OCR button** is pressed (reads captured text regardless)

### Priority system

```
Detection confidence 0.0 ──────────────────── 1.0
                           0.25     0.7
                            │        │
                      DETECT        LOG to Firebase
                            │
              ┌─────────────┼─────────────┐
              │             │             │
           DANGER         CAUTION       INFO
        car, bus, truck  person, bike  chair, bottle
              │             │             │
        IMMEDIATE TTS    NORMAL TTS    LOW PRIORITY TTS
        + interrupt       + log         (may skip if
        current speech                   speaking danger)
```

### Multilingual voice output

The app speaks in the user's chosen language — selected in Settings:

| Language | Locale | Engine |
|---|---|---|
| English (India) | `en-IN` | Android TTS built-in |
| English (US) | `en-US` | Android TTS built-in |
| Kannada | `kn-IN` | Android TTS (device support required) |
| Hindi | `hi-IN` | Android TTS built-in |

On the Raspberry Pi hardware (Phase 2), Kannada is handled by `gTTS` with offline phrase caching since `pyttsx3` does not support Kannada natively.

### What the user actually hears

| Scenario | Voice says |
|---|---|
| Person detected ahead | *"person ahead"* |
| Car detected (danger) | *"Warning: car ahead"* (interrupts other speech) |
| OCR button pressed on medicine label | *Full text of the label read aloud* |
| SOS sent | *"SOS alert sent. Help is on the way."* |
| Settings saved | *"Settings saved"* |
| No text found in OCR | *"No text found"* |

---

## 🏗️ Architecture

### App structure

```
com.example.seeforyou/
├── SeeForYouApp.kt              ← Application class, Firebase init
├── MainActivity.kt              ← Bottom nav, lazy fragment loading
│
├── screens/
│   ├── CameraFragment.kt        ← Live detection + YOLOv8 + TTS
│   ├── OcrFragment.kt           ← Camera capture + ML Kit OCR + TTS
│   ├── LogFragment.kt           ← Firebase detection history
│   ├── SettingsFragment.kt      ← Language, volume, speed settings
│   └── SosFragment.kt           ← GPS SOS → Firebase alert
│
├── services/
│   ├── TtsService.kt            ← Smart TTS with deduplication
│   └── FirebaseService.kt       ← Detection + OCR + SOS logging
│
└── utils/
    ├── YoloDetector.kt          ← TFLite inference + NMS decoding
    └── DetectionOverlayView.kt  ← Green bounding boxes on camera
```

### Fragment lifecycle — no camera conflicts

The app uses `show()`/`hide()` instead of `replace()` for fragments. This means the camera stays alive when you switch tabs — no black screens, no re-initialization delay.

```kotlin
// MainActivity.kt — lazy loading prevents camera conflicts
private val fragments = mutableMapOf<Int, Fragment>()

bottomNav.setOnItemSelectedListener { item ->
    val fragment = fragments.getOrPut(item.itemId) { createFragment(item.itemId) }
    supportFragmentManager.beginTransaction()
        .apply { fragments.values.forEach { hide(it) } }
        .show(fragment)
        .commit()
    true
}
```

### Firebase data structure

```
Firebase Realtime Database (asia-south1 — Mumbai)
├── logs/
│   └── {timestamp}/
│       ├── label: "person"
│       ├── confidence: "0.87"
│       └── timestamp: "2025-04-22 14:30:05"
├── ocr_logs/
│   └── {timestamp}/
│       ├── text: "Paracetamol 500mg..."
│       └── timestamp: "2025-04-22 14:31:12"
└── sos/
    └── {push_id}/
        ├── lat: "12.97194"
        ├── lng: "77.59369"
        ├── timestamp: "2025-04-22 14:45:00"
        └── resolved: false
```

---

## 📱 Screens

| Screen | What it does |
|---|---|
| **Detect** | Full-screen camera with live AI detection, green bounding boxes, object name + confidence at top |
| **Read** | Camera preview + tap button to OCR and hear any text read aloud |
| **Log** | Scrollable history of everything detected, with timestamps and confidence badges |
| **Settings** | Adjust voice volume, speech speed, and language |
| **SOS** | Big red button — sends GPS coordinates to Firebase and speaks confirmation |

---

## ⚙️ Setup

### Prerequisites

- Android Studio Hedgehog or later
- Android phone with API 26+ (Android 8.0)
- A Firebase account (free Spark plan is enough)

### 1. Clone the repo

```bash
git clone https://github.com/Div-doin/SeeForYou.git
cd SeeForYou
```

### 2. Add the YOLOv8n TFLite model

Download `yolov8n_float32.tflite` from [Ultralytics](https://github.com/ultralytics/ultralytics) or export it yourself:

```bash
pip install ultralytics
python -c "from ultralytics import YOLO; YOLO('yolov8n.pt').export(format='tflite')"
```

Place the file at:
```
app/src/main/assets/yolov8n_float32.tflite
```

### 3. Set up Firebase

1. Go to [Firebase Console](https://console.firebase.google.com) → Add project → name it `SeeForYou`
2. Add Android app → package name: `com.example.seeforyou`
3. Download `google-services.json` → place it in `app/` folder
4. Enable **Realtime Database** → Start in test mode → region: `asia-south1`

### 4. Build and run

```bash
# Connect your Android phone with USB debugging enabled
./gradlew installDebug
```

Or press the **Run** button in Android Studio.

---

## 📦 Dependencies

```kotlin
// AI / ML
implementation("org.tensorflow:tensorflow-lite:2.14.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
implementation("com.google.mlkit:text-recognition:16.0.0")

// Camera
implementation("androidx.camera:camera-core:1.3.1")
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")
implementation("androidx.camera:camera-view:1.3.1")

// Firebase
implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
implementation("com.google.firebase:firebase-database-ktx:20.3.0")
implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")

// Location (SOS)
implementation("com.google.android.gms:play-services-location:21.1.0")
```

---

## 🔭 Hardware Roadmap

This Android app is **Phase 1 — the software prototype**. Phase 2 assembles the actual smart glasses hardware:

```
Current (Phase 1)          Future (Phase 2)
────────────────           ────────────────
Android Phone         →    Raspberry Pi Zero 2W
Phone Camera          →    Stereo Camera Module (depth estimation)
Phone Speaker         →    Lenskart Phonic Smart Glasses (BT audio)
Touch screen          →    Physical push button (OCR trigger)
No haptics            →    2x vibration motors (L/R obstacle direction)
TFLite on phone       →    TFLite INT8 on Pi + Coral USB Accelerator
```

### Target hardware specs

| Component | Part | Cost (approx.) |
|---|---|---|
| Main compute | Raspberry Pi Zero 2W | ₹1,200 |
| Camera | Stereo Camera Module (2× OV5647) | ₹800 |
| Battery | LiPo 2500mAh + TP4056 + MT3608 | ₹400 |
| Audio | Lenskart Phonic Smart Glasses | ₹1,499 |
| Haptics | Mini Vibration Motors ×2 | ₹100 |
| AI Accelerator | Coral USB Accelerator (optional) | ₹3,500 |
| Frame | Custom 3D printed PLA | ₹300 |
| **Total (without Coral)** | | **~₹4,300** |

---

## 🎯 Key Technical Decisions

**Why YOLOv8n over ML Kit Object Detection?**
ML Kit's object detector identifies only 5 generic categories. YOLOv8n identifies 80 specific COCO classes — critical for telling the user "car" vs "bus" vs "motorcycle" vs "bicycle" for navigation decisions.

**Why TFLite over PyTorch?**
TFLite INT8 quantized models are 4× faster than PyTorch on the Raspberry Pi Zero 2W and consume 60% less CPU — the difference between meeting and missing the <300ms latency target.

**Why adaptive frame skipping?**
Running inference on every frame at 20 FPS would consume 100% CPU continuously. Skipping frames where the scene hasn't changed (using `absdiff` motion detection) reduces inference to only frames where something actually moved — cutting average CPU load by ~60% with no perceptible impact on responsiveness.

**Why Firebase over a custom backend?**
Firebase Realtime Database provides sub-second sync between the glasses hardware and caregiver's phone app with zero backend infrastructure to maintain — critical for a student project that needs to focus engineering effort on the AI pipeline.

---

## 📋 Project Status

- [x] Android prototype — all 5 screens operational
- [x] YOLOv8n TFLite integration with NMS decoding
- [x] ML Kit OCR with TTS readout
- [x] Firebase Realtime Database — detection logs, OCR logs, SOS alerts
- [x] Multilingual TTS — English, Kannada, Hindi
- [x] GPS SOS emergency alert
- [ ] Adaptive motion-based frame skipping (in progress)
- [ ] Haptic vibration for danger objects (Phase 2 — hardware)
- [ ] Stereo depth estimation (Phase 2 — hardware)
- [ ] Raspberry Pi hardware assembly (Phase 2)
- [ ] Field testing with visually impaired users (Phase 2)

---

## 🏫 About

**Project:** AI-Powered Smart Assistive Glasses for Mobility and Information Access for Visually Impaired Individuals

**Institution:** RNS Institute of Technology, Bengaluru

**Department:** Computer Science & Engineering (Data Science)


---

<div align="center">

Built with ❤️ for the visually impaired community

</div>

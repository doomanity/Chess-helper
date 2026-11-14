// chess helper for the pc (windows): reads the board on screen and draws the engine's moves over it.
// the board reading, move following and engine parsing are the phone app's own files, shared as they are
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.3")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.json:json:20240303")
    implementation("com.formdev:flatlaf:3.5.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// the phone app's files that have no android code in them
val shared = listOf(
    "BoardVision.kt", "Game.kt", "Tracker.kt", "PieceTemplates.kt", "HaikuParse.kt", "Candidates.kt",
    "Review.kt", "Accuracy.kt", "Safety.kt", "UciEngine.kt", "RemoteEngine.kt"
)
val sharedDir = layout.buildDirectory.dir("shared")
val copyShared by tasks.registering(Sync::class) {
    from("../app/src/main/java/com/example/chesshelper") { include(shared) }
    into(sharedDir.map { it.dir("com/example/chesshelper") })
}
sourceSets.main { kotlin.srcDir(sharedDir) }
tasks.named("compileKotlin") { dependsOn(copyShared) }

application {
    mainClass.set("com.example.chesshelper.pc.MainKt")
    // one java pixel = one screen pixel, so the captured board and the drawing line up on scaled displays
    applicationDefaultJvmArgs = listOf("-Dsun.java2d.uiScale=1")
}

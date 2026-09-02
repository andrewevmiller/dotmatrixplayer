plugins {
    id("com.android.application")
}

android {
    namespace = "com.dotgrid.mediawidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dotgrid.mediawidget"
        /*
         * minSdk 31: the floor of the highest widget in the bundle, not of this
         * module alone.
         *
         * One APK now carries four providers, and an APK has one minSdk. The
         * score tile is the binding one - see :scorewidget, where the three
         * size breakpoints are read through the API 31 options callbacks and
         * setViewLayoutWidth/Height. That cannot be guarded at runtime the way
         * a missing method can: targetCellWidth/targetCellHeight live in the
         * provider-info XML, which the host parses at install time, so a
         * lower floor would need a second provider behind res qualifiers and
         * a second entry in the widget picker.
         *
         * The media tile here already declares targetCellWidth/Height too, so
         * :app was only nominally a 26 module. :datawidget wants 29 and
         * :healthwidget wants 28; 31 covers both.
         *
         * Nothing's first phone shipped on Android 12, so nothing is lost.
         */
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No shipped dependencies on purpose. Everything here is framework API:
// android.media.session for the transport, RemoteViews for the surface, Canvas
// for the type and the scrub bar. A home-screen widget that pulls in AndroidX
// pays for it in APK size and cold-start for no benefit at this scope.
//
// JUnit is the one exception and it does not ship, matching the two sibling
// modules. It covers the typography roles, where the failure mode is silent:
// a face pointed at the wrong resource still builds and still draws, and is
// only wrong to someone reading the brand guideline.
dependencies {
    // The three sibling widgets. Each is an Android library holding its own
    // provider, config screen, boot receiver and resources; this is the only
    // application module, so their manifests merge into this one and all four
    // tiles ship in a single APK under a single applicationId.
    implementation(project(":datawidget"))
    implementation(project(":scorewidget"))
    implementation(project(":healthwidget"))

    testImplementation("junit:junit:4.13.2")
}

/*
 * A widget cannot render a custom font from XML. AppWidgetHostView inflates
 * through a CONTEXT_RESTRICTED context, and TextView only resolves an
 * android:fontFamily resource when !context.isRestricted() - so in a widget the
 * attribute is skipped in silence and the label comes out in the system face.
 *
 * The only way to get the Nothing faces onto a home screen is to draw them to a
 * bitmap in our own process (see TextRenderer) and ship an ImageView. This check
 * enforces that: any TextView reintroduced into a widget layout is a font
 * regression that would otherwise be invisible until someone looked at a phone.
 */
val verifyWidgetHasNoTextViews = tasks.register("verifyWidgetHasNoTextViews") {
    group = "verification"
    description = "Fails if a widget layout contains a TextView, which cannot carry a custom font."

    val layouts = listOf("widget_media.xml", "widget_media_compact.xml", "seek_strip.xml")
        .map { layout.projectDirectory.file("src/main/res/layout/$it").asFile }
    inputs.files(layouts)

    doLast {
        // Plain string split, not a regex: an escape sequence in a Gradle script
        // is one more thing that can silently be wrong, and this check exists
        // precisely because silent failures are the problem.
        val offenders = layouts.filter { it.exists() }.mapNotNull { file ->
            val count = file.readText().split("<TextView").size - 1
            if (count > 0) "  ${file.name}: $count TextView(s)" else null
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Widget layouts must not contain TextViews - a custom font cannot " +
                    "survive a restricted inflate, so the label would render in the " +
                    "system face. Draw it with TextRenderer into an ImageView.\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyWidgetHasNoTextViews) }

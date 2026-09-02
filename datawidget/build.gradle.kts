plugins {
    id("com.android.library")
}

android {
    namespace = "com.dotgrid.datawidget"
    resourcePrefix = "datawidget_"
    compileSdk = 36

    defaultConfig {
        /*
         * minSdk 29, where the sibling :app sits at 26.
         *
         * The only way a third-party app can read mobile data totals is
         * NetworkStatsManager.querySummaryForDevice(TYPE_MOBILE, subscriberId, ..).
         * Before Q that call wanted a real subscriber id, which meant holding
         * READ_PHONE_STATE - a dangerous runtime permission - purely to name the
         * SIM. Q then closed getSubscriberId() to everyone without carrier
         * privileges and, in the same release, made a null subscriberId mean
         * "every subscriber on the device". So from Q the query is both simpler
         * and cheaper in permissions than it ever was before it, and the
         * pre-Q path would be a second code path asking for more.
         *
         * Nothing's first phone shipped on Android 12, so nothing is lost.
         */
        minSdk = 29

        // Consumed by :app when it minifies. The provider, the boot receiver and
        // the config activity are named only from the manifest, so R8 has no
        // edge to them; as a library those keep rules have to travel with the
        // module rather than sit in an application-level proguardFiles.
        consumerProguardFiles("proguard-rules.pro")

        // No applicationId, targetSdk, versionCode or versionName: this module
        // no longer ships an APK. :app owns the app identity and the version,
        // and the merged manifest is where these components end up.
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

// Same rule as :app - framework API only at runtime. NetworkStatsManager,
// AppWidgetManager, RemoteViews and Canvas cover all of this, and a widget pays
// for every shipped dependency twice: once in APK size, once in cold-start on
// every repaint.
//
// JUnit is the one exception and it is not a shipped one. The rollover maths is
// the part of this app most likely to be quietly wrong - short months, leap
// days, the boundary at midnight - and those are cases you cannot check by
// looking at a phone on any given day.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

/*
 * See TextRenderer: AppWidgetHostView inflates through a CONTEXT_RESTRICTED
 * context, and TextView skips android:fontFamily when the context is restricted.
 * A TextView in a widget layout therefore renders in the system face with no
 * error anywhere - invisible until someone looks at a phone. Fail the build
 * instead.
 */
val verifyWidgetHasNoTextViews = tasks.register("verifyWidgetHasNoTextViews") {
    group = "verification"
    description = "Fails if a widget layout contains a TextView, which cannot carry a custom font."

    val layouts = listOf("widget_data.xml")
        .map { layout.projectDirectory.file("src/main/res/layout/$it").asFile }
    inputs.files(layouts)

    doLast {
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

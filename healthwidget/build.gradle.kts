plugins {
    id("com.android.library")
}

android {
    namespace = "com.dotgrid.healthwidget"
    resourcePrefix = "healthwidget_"
    compileSdk = 36

    defaultConfig {
        /*
         * minSdk 28, below the combined floor set by :app (31) and below
         * :scorewidget's own 31 and :datawidget's 29.
         *
         * Health Connect itself does not push this higher: the client library
         * (androidx.health.connect:connect-client) targets down to API 26, and
         * the manifest's two permission sets - android.permission.health.* from
         * the platform and androidx.health.permission.* from the Health Connect
         * APK's own declaration - are both requested permissions, not a minSdk
         * requirement; a device that predates either set simply grants nothing
         * from that half and Health Connect (if installed) answers from the
         * half it knows. The original standalone app pinned 28 without a
         * documented reason beyond that; nothing here needed it raised.
         *
         * Restated for the record because :app's minSdk is 31, not this
         * module's: an APK has one minSdk, and the score tile's
         * targetCellWidth/targetCellHeight usage (see :scorewidget) is what
         * actually sets the combined floor. 28 is covered by it with room
         * to spare.
         */
        minSdk = 28

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

/*
 * The one sibling that cannot stay framework-only.
 *
 * :app, :datawidget and :scorewidget all read their data through a framework
 * manager class. Health data has no such class - the platform's health
 * permissions exist, but the read path is Health Connect's own client, which
 * is a real dependency, not an optional convenience. androidx.activity is here
 * only for ComponentActivity's permission-request contract, which is how that
 * client's grant flow is launched; there is no Compose artifact, because
 * RemoteViews cannot host it and the settings screen is plain framework views
 * like every sibling's. Coroutines is here because the client's read calls are
 * suspend functions.
 *
 * Same versions the original standalone project had already resolved, carried
 * over as literal coordinates rather than a version catalog: this repo has
 * none, and starting one for four dependencies used by one of four modules
 * would be more machinery than the four dependencies are worth.
 */
dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.health.connect:connect-client:1.1.0-rc03")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

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

    val layouts = listOf("widget_health_dial.xml", "widget_health_rows.xml")
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

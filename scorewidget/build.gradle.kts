plugins {
    id("com.android.library")
}

android {
    namespace = "com.dotgrid.scorewidget"
    compileSdk = 36

    defaultConfig {
        /*
         * minSdk 31, above both siblings (:app at 26, :datawidget at 29).
         *
         * The reason is targetCellWidth / targetCellHeight in the provider
         * info. This widget is specified in launcher cells - 2x1, 4x1, 4x2 -
         * and those attributes are the only way to say so; before S a provider
         * could only state a dp size and hope the grid rounded the way it
         * meant. With three breakpoints that guess goes wrong three ways.
         *
         * S is also where RemoteViews gained setViewLayoutWidth and the
         * responsive-size callbacks that make a single provider serve three
         * layouts without a resize round-trip through the host.
         *
         * Nothing's first phone shipped on Android 12, so nothing is lost.
         */
        minSdk = 31

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

    testOptions {
        unitTests {
            /*
             * The unit tests run on the JVM against android.jar's stubs, where
             * every method throws "Stub!" unless this is set.
             *
             * Nothing under test here touches the framework - the season
             * windows, the ranking and the win-probability curve are all plain
             * arithmetic, which is exactly why they are the parts worth
             * testing. But the objects holding them sit in files that also hold
             * an android.util.LruCache or two, and initialising one of those
             * objects constructs its caches. Returning defaults lets the
             * constructor no-op so the arithmetic beside it can be reached.
             */
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/*
 * Same rule as the two siblings: framework API only at runtime.
 *
 * This module has one more reason for it than they do. It is the only one of
 * the three that talks to the network, and the obvious reaches - a JSON mapper,
 * an HTTP client - would each land a library in a process that exists to repaint
 * a tile. HttpsURLConnection and org.json are both in the platform and both
 * enough for one GET and one object walk. See EspnClient.
 *
 * JUnit is the exception and does not ship. What is tested is the arithmetic
 * that is easy to get quietly wrong and impossible to check by looking at a
 * phone on any given day - the offseason windows, the priority ordering, and
 * the win-probability curve.
 */
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

    val layouts = listOf(
        "widget_score_strip.xml",
        "widget_score_banner.xml",
        "widget_score_card.xml"
    ).map { layout.projectDirectory.file("src/main/res/layout/$it").asFile }
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

/*
 * RemoteViews will only inflate a view class annotated @RemoteView, and the list
 * is short and unguessable. Everything else throws
 * "Class not allowed to be inflated" at the launcher - which is not a build
 * error, not a lint warning, and not visible anywhere until the tile is dropped
 * on a home screen and fails to draw.
 *
 * Space is the trap this exists for. It is the obvious element to reach for as a
 * weighted spacer, it is in android.widget alongside everything that does work,
 * and it is a bare View subclass without the annotation - so it crashes the
 * inflate. It shipped here once and was found on a phone. Use an empty
 * FrameLayout instead.
 */
val verifyWidgetViewsAreRemotable = tasks.register("verifyWidgetViewsAreRemotable") {
    group = "verification"
    description = "Fails if a widget layout uses a view class RemoteViews cannot inflate."

    val layouts = listOf(
        "widget_score_strip.xml",
        "widget_score_banner.xml",
        "widget_score_card.xml"
    ).map { layout.projectDirectory.file("src/main/res/layout/$it").asFile }
    inputs.files(layouts)

    doLast {
        // Not the whole allowlist - just the classes someone is actually
        // likely to type into one of these files and be wrong about.
        val banned = listOf("Space", "View", "ConstraintLayout", "ScrollView", "Guideline")

        // A regex boundary, not a literal "<$it " / "<$it\n": the old check
        // missed a self-closed tag with no interior space ("<Space/>"), a
        // fully-qualified name ("<android.widget.Space"), and a CRLF line
        // ending ("<Space\r\n") - three ways to write the exact same crash
        // that would have slipped straight past it. The lookahead requires
        // whitespace, "/" or ">" right after the class name so "SpaceX" is
        // not flagged as "Space".
        val offenders = layouts.filter { it.exists() }.flatMap { file ->
            val text = file.readText()
            banned.filter { name ->
                Regex("<(?:[\\w.]*\\.)?$name(?=[\\s/>])").containsMatchIn(text)
            }.map { "  ${file.name}: <$it>" }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Widget layouts may only use view classes annotated @RemoteView. " +
                    "The launcher throws \"Class not allowed to be inflated\" for " +
                    "anything else, and nothing catches it before a phone does. " +
                    "For a weighted spacer use an empty FrameLayout.\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyWidgetViewsAreRemotable) }

tasks.named("preBuild") { dependsOn(verifyWidgetHasNoTextViews) }

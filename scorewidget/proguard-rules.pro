# The widget provider, the boot receiver and the config activity are all
# resolved by name from the manifest, never referenced from Kotlin - R8 has no
# edge to them and would otherwise be free to rename or drop them.
-keep class com.dotgrid.scorewidget.ScoreWidgetProvider { *; }
-keep class com.dotgrid.scorewidget.BootReceiver { *; }
-keep class com.dotgrid.scorewidget.ConfigActivity { *; }

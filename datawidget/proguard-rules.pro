# The widget provider, the boot receiver and the config activity are all
# resolved by name from the manifest, never referenced from Kotlin - R8 has no
# edge to them and would otherwise be free to rename or drop them.
-keep class com.dotgrid.datawidget.DataWidgetProvider { *; }
-keep class com.dotgrid.datawidget.BootReceiver { *; }
-keep class com.dotgrid.datawidget.ConfigActivity { *; }

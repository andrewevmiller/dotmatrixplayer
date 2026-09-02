# The widget provider and the boot receiver are only ever named from the
# manifest, so nothing in the code graph reaches them and R8 would strip both.
-keep class com.dotgrid.healthwidget.NothingHealthWidgetProvider { *; }
-keep class com.dotgrid.healthwidget.BootReceiver { *; }
-keep class com.dotgrid.healthwidget.ConfigActivity { *; }

# RemoteViews.setInt(id, "setColorFilter", ...) and friends reach framework
# view methods by name at apply() time, in the launcher's process. R8 cannot
# see those call sites at all.
-keepclassmembers class android.widget.ImageView {
    public void setColorFilter(int);
}

# Health Connect resolves record and aggregate types reflectively when it
# marshals them across the binder.
-keep class androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.aggregate.** { *; }

# WorkManager persists the worker class name and instantiates it reflectively.
# Preserve the class name and required constructor, but allow R8 to optimize its implementation.
-keep,allowoptimization class com.amanrealhere.personalgallery.data.thumbnail.ThumbnailGenerationWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

package nl.utwente.doomscroll.classifier

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import nl.utwente.doomscroll.model.AppCategory

class AppCategoryClassifier(context: Context) {

    private val categoryMap: Map<String, AppCategory>

    init {
        val json = context.assets.open("app_categories.json")
            .bufferedReader().use { it.readText() }
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        val adapter = moshi.adapter<Map<String, String>>(type)
        val raw = adapter.fromJson(json) ?: emptyMap()
        categoryMap = raw.mapValues { (_, v) ->
            try { AppCategory.valueOf(v) } catch (_: Exception) { AppCategory.OTHER }
        }
    }

    fun classify(packageName: String): AppCategory =
        categoryMap[packageName] ?: AppCategory.OTHER
}

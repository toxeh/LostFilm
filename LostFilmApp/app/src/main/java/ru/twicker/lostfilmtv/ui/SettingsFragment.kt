package ru.twicker.lostfilmtv.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import ru.twicker.lostfilmtv.network.CiadpiTuner
import ru.twicker.lostfilmtv.network.ImageCache
import ru.twicker.lostfilmtv.network.LostFilmAuth

/**
 * Settings screen (Leanback GuidedStep pattern):
 *  - Logout
 *  - Clear image cache
 *  - UDP Broadcast toggle + port
 *  - Back
 */
class SettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val ACTION_LOGOUT      = 1L
        private const val ACTION_CLEAR_CACHE = 2L
        private const val ACTION_RETUNE_DPI  = 4L
        private const val ACTION_BROADCAST_TOGGLE = 5L
        private const val ACTION_BROADCAST_PORT   = 6L
        private const val ACTION_BACK        = 3L
        private const val ACTION_AUTH_PORT    = 7L
        
        private const val PREFS_NAME = "lostfilm_settings"
        private const val KEY_BROADCAST_ENABLED = "broadcast_enabled"
        private const val KEY_BROADCAST_PORT = "broadcast_port"
        const val DEFAULT_BROADCAST_PORT = 41000
        private const val KEY_AUTH_BROADCAST_PORT = "auth_broadcast_port"
        const val DEFAULT_AUTH_BROADCAST_PORT = 41001
        
        fun isBroadcastEnabled(ctx: Context): Boolean {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BROADCAST_ENABLED, false)
        }
        
        fun getBroadcastPort(ctx: Context): Int {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BROADCAST_PORT, DEFAULT_BROADCAST_PORT)
        }
        
        fun getAuthBroadcastPort(ctx: Context): Int {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_AUTH_BROADCAST_PORT, DEFAULT_AUTH_BROADCAST_PORT)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        val cacheKb = ImageCache.totalSizeBytes(requireContext()) / 1024
        val strategy = CiadpiTuner.getSavedStrategy(requireContext())?.name ?: "не выбрана"
        val ttl = CiadpiTuner.getSavedTtl(requireContext())
        val ttlStr = if (ttl > 0) " · TTL=$ttl" else ""
        val bcStatus = if (isBroadcastEnabled(requireContext())) 
            "вкл (порт ${getBroadcastPort(requireContext())})" else "выкл"
        return Guidance(
            "Настройки",
            "Кэш: ${cacheKb} КБ · DPI: $strategy$ttlStr · Broadcast: $bcStatus",
            "",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_LOGOUT)
            .title("Выйти из аккаунта")
            .build()

        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_CLEAR_CACHE)
            .title("Очистить кэш картинок")
            .build()

        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_RETUNE_DPI)
            .title("Перенастроить обход DPI")
            .description("Перебрать стратегии и выбрать лучшую")
            .build()

        val bcEnabled = isBroadcastEnabled(requireContext())
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_BROADCAST_TOGGLE)
            .title("UDP Broadcast торрентов")
            .description(if (bcEnabled) "Включен" else "Выключен")
            .build()

        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_BROADCAST_PORT)
            .title("Порт broadcast")
            .description("${getBroadcastPort(requireContext())}")
            .descriptionEditable(true)
            .build()

        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_AUTH_PORT)
            .title("Порт авторизации по LAN")
            .description("${getAuthBroadcastPort(requireContext())}")
            .descriptionEditable(true)
            .build()

        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_BACK)
            .title("Назад")
            .build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_LOGOUT -> {
                LostFilmAuth.logout(requireContext())
                requireActivity().finish()
                startActivity(
                    android.content.Intent(requireContext(), ru.twicker.lostfilmtv.MainActivity::class.java)
                )
            }
            ACTION_CLEAR_CACHE -> {
                val sizeMb = "%.1f".format(ImageCache.totalSizeBytes(requireContext()) / 1024f / 1024f)
                ImageCache.clear(requireContext())
                Toast.makeText(requireContext(), "Кэш очищен (было ${sizeMb} МБ)", Toast.LENGTH_SHORT).show()
                refreshFragment()
            }
            ACTION_RETUNE_DPI -> {
                CiadpiTuner.clearSavedStrategy(requireContext())
                Toast.makeText(requireContext(), "Перезапустите приложение для подбора стратегии", Toast.LENGTH_LONG).show()
                requireActivity().finish()
                startActivity(
                    android.content.Intent(requireContext(), ru.twicker.lostfilmtv.MainActivity::class.java)
                )
            }
            ACTION_BROADCAST_TOGGLE -> {
                val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val newVal = !prefs.getBoolean(KEY_BROADCAST_ENABLED, false)
                prefs.edit().putBoolean(KEY_BROADCAST_ENABLED, newVal).apply()
                val msg = if (newVal) "Broadcast включен (порт ${getBroadcastPort(requireContext())})" 
                          else "Broadcast выключен"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                refreshFragment()
            }
            ACTION_BROADCAST_PORT -> {
                // Port is edited via descriptionEditable — handled in onGuidedActionEditedAndProceed
            }
            ACTION_AUTH_PORT -> {
                // Port is edited via descriptionEditable — handled in onGuidedActionEditedAndProceed
            }
            ACTION_BACK -> parentFragmentManager.popBackStack()
        }
    }
    
    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val portStr = action.description?.toString()?.trim() ?: ""
        val port = portStr.toIntOrNull()
        when (action.id) {
            ACTION_BROADCAST_PORT -> {
                if (port != null && port in 1024..65535) {
                    requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_BROADCAST_PORT, port).apply()
                    Toast.makeText(requireContext(), "Порт: $port", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Некорректный порт (1024-65535)", Toast.LENGTH_SHORT).show()
                    action.description = "${getBroadcastPort(requireContext())}"
                }
            }
            ACTION_AUTH_PORT -> {
                if (port != null && port in 1024..65535) {
                    requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_AUTH_BROADCAST_PORT, port).apply()
                    Toast.makeText(requireContext(), "Порт авторизации: $port", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Некорректный порт (1024-65535)", Toast.LENGTH_SHORT).show()
                    action.description = "${getAuthBroadcastPort(requireContext())}"
                }
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }
    
    private fun refreshFragment() {
        fragmentManager?.beginTransaction()
            ?.replace(id, SettingsFragment())
            ?.commit()
    }
}

package ru.twicker.lostfilmtv.ui

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
 *  - Back
 */
class SettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val ACTION_LOGOUT      = 1L
        private const val ACTION_CLEAR_CACHE = 2L
        private const val ACTION_RETUNE_DPI  = 4L
        private const val ACTION_BACK        = 3L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        val cacheKb = ImageCache.totalSizeBytes(requireContext()) / 1024
        val strategy = CiadpiTuner.getSavedStrategy(requireContext())?.name ?: "не выбрана"
        val ttl = CiadpiTuner.getSavedTtl(requireContext())
        val ttlStr = if (ttl > 0) " · TTL=$ttl" else ""
        return Guidance(
            "Настройки",
            "Кэш: ${cacheKb} КБ · DPI: $strategy$ttlStr",
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

        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_BACK)
            .title("Назад")
            .build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_LOGOUT -> {
                LostFilmAuth.logout(requireContext())
                // Return to login screen
                requireActivity().finish()
                startActivity(
                    android.content.Intent(requireContext(), ru.twicker.lostfilmtv.MainActivity::class.java)
                )
            }
            ACTION_CLEAR_CACHE -> {
                val sizeMb = "%.1f".format(ImageCache.totalSizeBytes(requireContext()) / 1024f / 1024f)
                ImageCache.clear(requireContext())
                Toast.makeText(requireContext(), "Кэш очищен (было ${sizeMb} МБ)", Toast.LENGTH_SHORT).show()
                // Refresh subtitle
                fragmentManager?.beginTransaction()
                    ?.replace(id, SettingsFragment())
                    ?.commit()
            }
            ACTION_RETUNE_DPI -> {
                CiadpiTuner.clearSavedStrategy(requireContext())
                Toast.makeText(requireContext(), "Перезапустите приложение для подбора стратегии", Toast.LENGTH_LONG).show()
                requireActivity().finish()
                startActivity(
                    android.content.Intent(requireContext(), ru.twicker.lostfilmtv.MainActivity::class.java)
                )
            }
            ACTION_BACK -> parentFragmentManager.popBackStack()
        }
    }
}

# AutoFarm BS — учебный Android-концепт (Kotlin + Jetpack Compose)

> ⚠️ **Дисклеймер:** автоматизация и читы (aimbot, auto-dodge, killaura, speed) могут нарушать Terms of Service Brawl Stars и привести к бану аккаунта. Это учебный/исследовательский концепт; используйте на свой риск. Проект не связан с Supercell.

## Состояние проекта

Это исходный прототип, а не готовый надёжный бот. Он содержит Compose UI, DataStore, MediaProjection, AccessibilityService, эвристическое распознавание по OpenCV/ML Kit и цикл состояний. Реальное распознавание экранов **не заработает без шаблонов**, а координаты и эвристики необходимо калибровать на конкретном устройстве. Автоматический выбор бойца и режима не реализован: прототип работает с бойцом/режимом, уже выбранными в самой игре. Портреты и шаблоны игры не включены.

## Сборка APK автоматически через GitHub (без Android Studio)

1. Создайте репозиторий на [GitHub](https://github.com/new). Для простоты можно выбрать Private.
2. Распакуйте этот архив на компьютере и загрузите **содержимое папки `AutoFarmBS`** в репозиторий. В корне репозитория должны находиться `settings.gradle.kts`, `build.gradle.kts`, папка `app` и папка `.github`.
3. Откройте вкладку **Actions** репозитория. Если GitHub попросит разрешить workflows, подтвердите.
4. В списке выберите **Build Android APK**, нажмите **Run workflow** → **Run workflow**. Также сборка запускается при push в ветку `main` или `master`.
5. Откройте завершившийся запуск workflow. Внизу страницы, в разделе **Artifacts**, скачайте `AutoFarmBS-debug-apk.zip`.
6. Распакуйте скачанный ZIP: внутри будет `app-debug.apk`. Перенесите APK на Android и установите, разрешив установку из этого источника, если система спросит.

Если сборка упала, откройте шаг **Build debug APK** и скопируйте последние строки ошибки. Workflow не подписывает приложение для публикации в Google Play — это debug APK для тестирования.

## Как собрать APK

1. Установите **Android Studio** (актуальную стабильную версию) и Android SDK Platform 34, Build Tools и JDK 17.
2. Распакуйте архив и откройте в Android Studio папку `AutoFarmBS` — ту, где лежат `settings.gradle.kts` и `build.gradle.kts`.
3. Дождитесь Gradle Sync. При запросе установите недостающие SDK-компоненты.
4. Для проверки выберите **Build → Make Project**.
5. Для APK: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. APK обычно появится в `app/build/outputs/apk/debug/app-debug.apk`. Передавайте файл на телефон и разрешите установку из этого источника. Не устанавливайте APK, если не доверяете источнику.

Командная сборка из корня проекта (после установки Android SDK и настройки `ANDROID_HOME`):

```bash
./gradlew assembleDebug
```

Если в архиве отсутствует `gradlew`, откройте проект в Android Studio и используйте её Gradle tooling, либо создайте Gradle Wrapper через установленный Gradle. В текущем окружении APK не собирался: Android SDK/Gradle не предоставлены, поэтому совместимость сборки не подтверждена.

## Первый запуск на телефоне

1. Откройте приложение и прочитайте дисклеймер на главном экране.
2. Включите службу специальных возможностей **AutoFarm BS** в системных настройках Android.
3. Разрешите отображение поверх других приложений, если хотите видеть кнопку STOP.
4. Нажмите **СТАРТ** и подтвердите системный запрос захвата экрана.
5. Для остановки нажмите **СТОП** в приложении или плавающей кнопке. Android может показывать постоянное уведомление во время захвата.

## Шаблоны Vision

Добавляйте PNG в `app/src/main/assets/templates/`, обрезанные из скриншотов с шириной кадра 1280 px. Имена, которые ищет код:

- `hud_attack.png` — индикатор HUD матча;
- `btn_play.png` — кнопка игры в лобби;
- `btn_proceed.png` — продолжить после результата;
- `btn_claim.png` — забрать награду;
- `btn_close.png`, `btn_ok.png` — закрытие диалогов;
- `victory.png` — надпись победы;
- `super_ready.png`, `gadget_ready.png` — готовность способностей.

Шаблоны интерфейса могут перестать совпадать после обновлений игры, смены языка, разрешения или масштаба. Не используйте автоматические тапы, пока не проверили координаты и распознавание на тестовом устройстве.

## Псевдокод цикла (идея, не гарантия побед)

```text
state = MENU
while running:
    frame = capture_screen()
    screen = classify(frame)
    if screen == UNKNOWN:
        log("экран не распознан")
        retry_with_backoff()
        continue
    if screen == LOBBY:
        if desired_mode_and_brawler_are_not_selected:
            notify_user_to_select_them_manually()
        else:
            tap_play_if_template_matches()
    elif screen == MATCH:
        detect_enemies_and_hud()
        if enabled(AIMBOT): aim_and_attack_heuristically()
        if enabled(AUTO_DODGE): choose_lateral_move()
        if enabled(KILLAURA): adjust_distance_and_attack()
        if enabled(SPEED): apply_calibrated_micro_swipes()
    elif screen == RESULT:
        update_match_and_win_counters()
        tap_continue_if_template_matches()
    elif screen in [REWARD, POPUP]:
        tap_claim_or_close_only_if_template_matches()
    persist_config_and_stats()
    sleep(configured_delay)
```

## Важные ограничения

- `AccessibilityService` жесты не являются точным многопальцевым контроллером; некоторые игры/версии Android могут отклонять или прерывать жесты.
- Захват экрана требует явного согласия Android; это не работает скрытно.
- Счётчик кубков OCR эвристический и может читать посторонние числа.
- Не подключайте аккаунт, пароль или сторонние API. Этот проект не использует API Supercell.

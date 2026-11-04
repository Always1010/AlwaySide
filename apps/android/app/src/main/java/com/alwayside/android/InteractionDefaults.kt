package com.alwayside.android

/** Upgrade only the complete legacy idle default, never a partly customized gesture scheme. */
internal object InteractionDefaults {
    const val VERSION = 2
    fun upgrade(config: InteractionConfig): InteractionConfig =
        if (config.singleAction == AssistantActions.SCREEN && config.doubleAction == AssistantActions.AUDIO &&
            config.tripleAction == AssistantActions.NONE && config.longAction == AssistantActions.MENU)
            config.copy(singleAction=AssistantActions.MENU,longAction=AssistantActions.SCREEN)
        else config
}

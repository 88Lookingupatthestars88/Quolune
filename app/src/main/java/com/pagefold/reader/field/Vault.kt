package com.pagefold.reader.field

/**
 * 库房六行的账。
 *
 * 定音页切走时整棵组合销毁，切回来是新建的。账若只放在 remember 里，每次回来都先画 0、
 * 等扫完才变真数，看起来就是闪一下。这里留一份，重建时拿它当初值。
 */
object Vault {
    @Volatile var audioBytes = 0L
    @Volatile var audioCount = 0
    @Volatile var bookBytes = 0L
    @Volatile var bookCount = 0
    @Volatile var voiceBytes = 0L
    @Volatile var voiceCount = 0
    @Volatile var bgBytes = 0L
    @Volatile var bgCount = 0
    @Volatile var coverBytes = 0L
    @Volatile var coverCount = 0
    @Volatile var trialCount = 0
}

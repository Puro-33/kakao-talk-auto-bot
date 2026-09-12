package com.example.kakaotalkautobot

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** One runner filter keeps all model-free storage and room-selection regressions together. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ConversationStoreInstrumentedTest::class,
    RoomTargetAdapterInstrumentedTest::class,
    BotManagerRoomBindingInstrumentedTest::class
)
class ConversationStorageSuite

package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTitleTest {
    @Test fun retainsExistingTitleFormattingAndFlatLinkSyntax() {
        assertEquals("订阅将在下个月自动续费", ConversationTitle.fromAnswer("译文： [订阅将在下个月自动续费](https://example.com)"))
        assertEquals("图片标题", ConversationTitle.fromAnswer("## **![图片标题](arbitrary address with spaces)**"))
        assertEquals("正文", ConversationTitle.fromAnswer("[](anything)正文"))
        assertEquals("[内层", ConversationTitle.fromAnswer("[[内层](url)"))
        assertEquals("标签c)", ConversationTitle.fromAnswer("[标签](a(b)c)"))
        assertEquals("![未闭合", ConversationTitle.fromAnswer("![未闭合"))
        assertEquals("有效内容", ConversationTitle.fromAnswer("翻译如下：\n> `有效内容`"))
        assertEquals("😀".repeat(32), ConversationTitle.fromAnswer("😀".repeat(40)))
    }

    @Test(timeout = 5000) fun handlesLongUnclosedLabelsAndAddressesInLinearTime() {
        val labels = "[".repeat(262144)
        val addresses = "[x](".repeat(65536)
        assertEquals("[".repeat(32), ConversationTitle.fromAnswer(labels))
        assertEquals(addresses.take(32), ConversationTitle.fromAnswer(addresses))
        assertEquals("[".repeat(32), ConversationTitle.fromAnswer(labels + "](any address)"))
    }
}

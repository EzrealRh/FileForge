package com.fileforge.converter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileforge.converter.ui.WorkbenchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工作台状态机的 JVM 真测试（Robolectric 提供安卓环境）：
 * 分享文本导入这条最常用的入口链路 —— 文件落盘、登记、选中、通知，一步不能少。
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkbenchViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 导入跑在真实 IO 线程上：轮询等到位为止。withContext 让 delay 走真实时间，不被 runTest 的虚拟时钟空转。 */
    private suspend fun TestScope.awaitItemCount(vm: WorkbenchViewModel, count: Int) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) {
                while (vm.state.value.items.size < count) {
                    delay(50)
                }
            }
        }
    }

    @Test
    fun `分享进来的文字落成工作台里的 txt 并被选中`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = WorkbenchViewModel(application)

        viewModel.importPlainText("你好，Robolectric。", "剪贴板")
        awaitItemCount(viewModel, 1)

        val state = viewModel.state.value
        assertEquals(1, state.items.size, "导入后工作台里就该有这一份")
        val imported = state.items.single()
        assertTrue(imported.name.endsWith(".txt"), "名字要带扩展名：${imported.name}")
        assertEquals("你好，Robolectric。", imported.file.readText())
        assertEquals(setOf(imported.id), state.selection, "导入的要自动选中，方便接着转换")
        assertTrue(state.loaded, "装载完成标记要亮起来")
    }

    @Test
    fun `两次导入同一标题会自动避让不互相覆盖`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = WorkbenchViewModel(application)

        viewModel.importPlainText("第一份", "剪贴板")
        awaitItemCount(viewModel, 1)
        viewModel.importPlainText("第二份", "剪贴板")
        awaitItemCount(viewModel, 2)

        val state = viewModel.state.value
        assertEquals(2, state.items.size)
        assertEquals(2, state.items.map { it.name }.toSet().size, "两份名字不能相同")
        assertEquals("第二份", state.items.first().file.readText(), "选中的是最新一份")
    }
}

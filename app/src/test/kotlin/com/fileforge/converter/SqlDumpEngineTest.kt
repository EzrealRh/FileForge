package com.fileforge.converter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.TextEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqlDumpEngineTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engine = TextEngine(Workspace(app))

    private fun item(): WorkItem {
        val file = File(app.cacheDir, "dump.sql")
        file.writeText(
            """
            CREATE TABLE users (id INT, name TEXT);
            INSERT INTO users VALUES (1,'Alice, A.'),(2,'O''Brien');
            CREATE TABLE orders (id INT, amount DECIMAL(10,2));
            INSERT INTO orders VALUES (10,9.50);
            """.trimIndent(),
        )
        return WorkItem(id = 1, name = "dump.sql", file = file, kind = FileKind.Text, size = file.length(), addedAt = 0L)
    }

    @Test
    fun `每张表出一份 CSV，表名筛选不区分大小写`() {
        val all = engine.sqlToCsv(item(), Operation.SqlToCsv(""))
        assertEquals(2, all.size)
        val users = all.first { it.name.contains("users") }.file.readText()
        assertTrue(users, users.startsWith("id,name\n"))
        assertTrue(users, users.contains("\"Alice, A.\""))
        assertTrue(users, users.contains("O'Brien"))
        val orders = all.first { it.name.contains("orders") }.file.readText()
        assertTrue(orders, orders.contains("9.50"))

        val only = engine.sqlToCsv(item(), Operation.SqlToCsv("ORDERs"))
        assertEquals(1, only.size)
        assertTrue(only[0].name.contains("orders"))
    }

    @Test
    fun `表名写错要点名这份 dump 有哪些表`() {
        val error = runCatching { engine.sqlToCsv(item(), Operation.SqlToCsv("nope")) }.exceptionOrNull()
        assertTrue(error!!.message ?: "", error.message!!.contains("users"))
        assertTrue(error.message ?: "", error.message!!.contains("orders"))
    }
}

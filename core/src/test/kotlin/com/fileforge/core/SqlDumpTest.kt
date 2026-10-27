package com.fileforge.core

import com.fileforge.core.data.SqlDump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SQL dump 解析：只认建表列与 INSERT 元组；引号、逗号、NULL、多行与分号都要对。 */
class SqlDumpTest {

    @Test
    fun `两张表、引号里的逗号和分号、NULL 与多行都读对`() {
        val sql = """
            -- 这是注释
            create table `users` (
              `id` INT PRIMARY KEY,
              `name` VARCHAR(20),
              `note` TEXT,
              PRIMARY KEY (`id`)
            );
            INSERT INTO `users` (`id`,`name`,`note`) VALUES
              (1,'Alice, A.','a;b'),
              (2,'O''Brien',NULL),
              (3,'空','');
            CREATE TABLE orders (id INT, amount DECIMAL(10,2));
            INSERT INTO orders VALUES (10,9.50),(11,NULL);
        """.trimIndent()
        val tables = SqlDump.parse(sql)
        assertEquals(listOf("users", "orders"), tables.map { it.name })
        val users = tables[0]
        assertEquals(listOf("id", "name", "note"), users.columns)
        assertEquals(3, users.rows.size)
        assertEquals("Alice, A.", users.rows[0][1])
        assertEquals("a;b", users.rows[0][2])
        assertEquals("O'Brien", users.rows[1][1])
        assertNull(users.rows[1][2])
        assertEquals("", users.rows[2][2])

        val orders = tables[1]
        assertEquals(listOf("id", "amount"), orders.columns)
        assertEquals(listOf("10", "9.50"), orders.rows[0])
        assertNull(orders.rows[1][1])

        val csv = SqlDump.toCsv(users)
        assertTrue(csv.startsWith("id,name,note\n"), csv)
        assertTrue(csv.contains("\"Alice, A.\""), csv)
        assertTrue(csv.contains("O'Brien"), csv)
        assertTrue(csv.contains("\"a;b\"") || csv.contains("a;b"), csv)
    }

    @Test
    fun `没有建表时从 INSERT 的列名推断，值比列多就补 cN`() {
        val tables = SqlDump.parse("INSERT INTO t (a,b) VALUES (1,2); INSERT INTO t VALUES (3,4,5);")
        assertEquals(1, tables.size)
        assertEquals(listOf("a", "b", "c3"), tables[0].columns)
        assertEquals(2, tables[0].rows.size)
        assertEquals(listOf("3", "4", "5"), tables[0].rows[1])
    }

    @Test
    fun `不是 SQL 就什么都不认，不静默造表`() {
        assertTrue(SqlDump.parse("hello world\n这不是 SQL").isEmpty())
        assertTrue(SqlDump.parse("UPDATE t SET a=1;").isEmpty(), "UPDATE 不执行也不导出")
    }
}

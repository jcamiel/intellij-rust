/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import org.intellij.lang.annotations.Language
import org.rust.ExpandMacros

@ExpandMacros  // todo - нужно чтобы включить энергичное построение DefMap
class RsDefMapSingleFileCachingTest : RsDefMapCachingTestBase() {

    private fun type(text: String = "a"): () -> Unit = {
        myFixture.type(text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun replaceFileContent(after: String): () -> Unit = {
        val virtualFile = myFixture.file.virtualFile
        runWriteAction {
            VfsUtil.saveText(virtualFile, after)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun doTestChanged(action: () -> Unit, @Language("Rust") code: String) {
        InlineFile(code).withCaret()
        doTest(action, shouldChange = true)
    }

    private fun doTestNotChanged(action: () -> Unit, @Language("Rust") code: String) {
        InlineFile(code).withCaret()
        doTest(action, shouldChange = false)
    }

    private fun doTest(
        @Language("Rust") before: String,
        @Language("Rust") after: String,
        shouldChange: Boolean
    ) {
        InlineFile(before)
        doTest(replaceFileContent(after), shouldChange)
    }

    private fun doTestChanged(
        @Language("Rust") before: String,
        @Language("Rust") after: String
    ) = doTest(before, after, shouldChange = true)

    private fun doTestNotChanged(
        @Language("Rust") before: String,
        @Language("Rust") after: String
    ) = doTest(before, after, shouldChange = false)

    fun `test edit function body`() = doTestNotChanged(type(), """
        fn foo() {/*caret*/}
    """)

    fun `test edit function arg name`() = doTestNotChanged(type(), """
        fn foo(x/*caret*/: i32) {}
    """)

    fun `test edit function name`() = doTestChanged(type(), """
        fn foo/*caret*/(x: i32) {}
    """)

    fun `test add function in empty file`() = doTestChanged("""

    """, """
        fn bar() {}
    """)

    fun `test add function to end of file`() = doTestChanged("""
        fn foo() {}
    """, """
        fn foo() {}
        fn bar() {}
    """)

    fun `test add function to beginning of file`() = doTestChanged("""
        fn foo() {}
    """, """
        fn bar() {}
        fn foo() {}
    """)

    fun `test swap functions`() = doTestNotChanged("""
        fn foo() {}
        fn bar() {}
    """, """
        fn bar() {}
        fn foo() {}
    """)

    fun `test change item visibility 1`() = doTestChanged("""
        fn foo() {}
    """, """
        pub fn foo() {}
    """)

    fun `test change item visibility 2`() = doTestChanged("""
        pub fn foo() {}
    """, """
        fn foo() {}
    """)

    fun `test change item visibility 3`() = doTestChanged("""
        pub fn foo() {}
    """, """
        pub(crate) fn foo() {}
    """)

    fun `test change item visibility 4`() = doTestNotChanged("""
        pub(crate) fn foo() {}
    """, """
        pub(in crate) fn foo() {}
    """)

    fun `test change item visibility 5`() = doTestNotChanged("""
        fn foo() {}
    """, """
        pub(self) fn foo() {}
    """)

    fun `test add item with same name in different namespace`() = doTestChanged("""
        fn foo() {}
    """, """
        fn foo() {}
        mod foo {}
    """)

    fun `test remove item with same name in different namespace`() = doTestChanged("""
        fn foo() {}
        mod foo {}
    """, """
        fn foo() {}
    """)

    fun `test change import 1`() = doTestChanged("""
        use aaa::bbb;
    """, """
        use aaa::ccc;
    """)

    fun `test change import 2`() = doTestChanged("""
        use aaa::{bbb, ccc};
    """, """
        use aaa::{bbb, ddd};
    """)

    fun `test swap imports`() = doTestNotChanged("""
        use aaa::bbb;
        use aaa::ccc;
    """, """
        use aaa::ccc;
        use aaa::bbb;
    """)

    fun `test swap paths in use group`() = doTestNotChanged("""
        use aaa::{bbb, ccc};
    """, """
        use aaa::{ccc, bbb};
    """)

    fun `test change import visibility`() = doTestChanged("""
        use aaa::bbb;
    """, """
        pub use aaa::bbb;
    """)

    fun `test change extern crate 1`() = doTestChanged("""
        extern crate foo;
    """, """
        extern crate bar;
    """)

    fun `test change extern crate 2`() = doTestChanged("""
        extern crate foo;
    """, """
        extern crate foo as bar;
    """)

    fun `test add macro_use to extern crate`() = doTestChanged("""
        extern crate foo;
    """, """
        #[macro_use]
        extern crate foo;
    """)

    fun `test change extern crate visibility`() = doTestChanged("""
        extern crate foo;
    """, """
        pub extern crate foo;
    """)

    fun `test change macro call 1`() = doTestChanged("""
        foo!();
    """, """
        bar!();
    """)

    fun `test change macro call 2`() = doTestChanged("""
        foo!();
    """, """
        foo!(bar);
    """)

    fun `test swap macro calls`() = doTestChanged("""
        foo1!();
        foo2!();
    """, """
        foo2!();
        foo1!();
    """)
}

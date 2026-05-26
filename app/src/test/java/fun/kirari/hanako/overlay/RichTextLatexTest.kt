package `fun`.kirari.hanako.overlay

import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RichTextLatexTest {
    @Test
    fun processLatex_stripsInlineMathDelimiters() {
        assertEquals(
            """a + b""",
            processLatex("""${'$'}a + b${'$'}""")
        )
    }

    @Test
    fun processLatex_stripsBlockMathDelimiters() {
        assertEquals(
            """\begin{bmatrix}1 & 2 \\ 3 & 4\end{bmatrix}""",
            processLatex("""$$\begin{bmatrix}1 & 2 \\ 3 & 4\end{bmatrix}$$""")
        )
    }

    @Test
    fun preprocessMarkdown_keepsExactMatrixSourceIntact() {
        val source = """已知矩阵 \( A = \begin{bmatrix} 3 & 0 & 0 \\ 0 & a & b \\ 0 & 2 & 3 \end{bmatrix} \) 和 \( B = \begin{bmatrix} 3 & 0 & 0 \\ 0 & 4 & 0 \\ 0 & 0 & -1 \end{bmatrix} \) 相似,则 \( b = \) _ ."""

        val result = parseMarkdown(source)

        assertEquals(
            """已知矩阵 $ A = \begin{bmatrix} 3 & 0 & 0 \\ 0 & a & b \\ 0 & 2 & 3 \end{bmatrix} $ 和 $ B = \begin{bmatrix} 3 & 0 & 0 \\ 0 & 4 & 0 \\ 0 & 0 & -1 \end{bmatrix} $ 相似,则 $ b = $ _ .""",
            result.preprocessed
        )
        assertNotNull(result.astTree.findChildRecursive(GFMElementTypes.INLINE_MATH))
    }
}

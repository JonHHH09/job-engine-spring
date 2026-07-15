package org.instruct.jobenginespring.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class DocumentCoverageEdgeCaseTests {

    @TempDir
    Path tempDir;

    @Test
    void fileIoHelpersMapRacesToSafeValidationErrors() throws Exception {
        Path disappeared = tempDir.resolve("disappeared.pdf");

        Method readContent = method(DocumentStorageService.class, "readContent", Path.class);
        assertApplicationException(readContent, disappeared);

        assertThrows(ApplicationException.class, () -> PdfTextExtractionService.readBoundedContent(disappeared));

        Path shortPdf = Files.write(tempDir.resolve("short.pdf"), new byte[]{'%', 'P'});
        assertThrows(ApplicationException.class, () -> PdfTextExtractionService.readLocalPdfSnapshot(shortPdf));

        PDDocument brokenDocument = mock(PDDocument.class);
        doThrow(new IOException("close failed")).when(brokenDocument).close();
        Method closeDocument = method(PdfTextExtractionService.class, "closeDocument", PDDocument.class);
        Exception closeFailure = assertThrows(Exception.class, () -> closeDocument.invoke(null, brokenDocument));
        assertTrue(closeFailure.getCause() instanceof IllegalStateException);
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static void assertApplicationException(Method method, Object... arguments) {
        Exception exception = assertThrows(Exception.class, () -> method.invoke(null, arguments));
        assertTrue(exception.getCause() instanceof ApplicationException);
    }
}

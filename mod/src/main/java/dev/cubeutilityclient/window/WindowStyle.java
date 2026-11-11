package dev.cubeutilityclient.window;

import com.mojang.logging.LogUtils;
import com.sun.jna.Function;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.packs.resources.IoSupplier;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;

/** The game window: the client's icon, and on Windows a dark title bar instead of a white one. */
public final class WindowStyle {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    /** The same setting on Windows 10 builds before 20H1. */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;
    private static final int DWMWA_BORDER_COLOR = 34, DWMWA_CAPTION_COLOR = 35, DWMWA_TEXT_COLOR = 36;
    private static final int WM_NCACTIVATE = 0x0086;
    /** COLORREF is 0x00BBGGRR: #06090C, the menus' darkest colour, and #C5CDCF for the title. */
    private static final int CAPTION = 0x000C0906, TEXT = 0x00CFCDC5;

    private WindowStyle() {
    }

    /** Asks Windows for a dark title bar. Windows 11 also takes the menus' exact colours. */
    public static void darkTitleBar(long sdlWindow) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        try {
            int properties = SDLVideo.SDL_GetWindowProperties(sdlWindow);
            long hwnd = SDLProperties.SDL_GetPointerProperty(properties, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
            if (hwnd == 0L) {
                LOGGER.info("Dark title bar: no Windows handle for window {} (properties {})", Long.toHexString(sdlWindow), properties);
                return;
            }
            Pointer handle = new Pointer(hwnd);
            Function dwm = Function.getFunction("dwmapi", "DwmSetWindowAttribute");
            int dark = set(dwm, handle, DWMWA_USE_IMMERSIVE_DARK_MODE, 1);
            if (dark != 0) {
                dark = set(dwm, handle, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, 1);
            }
            // Windows 10 refuses these and keeps its own dark grey; Windows 11 matches the menus.
            int caption = set(dwm, handle, DWMWA_CAPTION_COLOR, CAPTION);
            set(dwm, handle, DWMWA_BORDER_COLOR, CAPTION);
            set(dwm, handle, DWMWA_TEXT_COLOR, TEXT);
            // 0 is success; Windows 10 answers 80070057 for the colours.
            LOGGER.info("Dark title bar: dark mode {}, colours {}", Integer.toHexString(dark), Integer.toHexString(caption));
            // Windows 10 keeps showing the old title bar until the window is next activated, so
            // have Windows draw it inactive and back. This goes straight to Windows' own handler:
            // sent to the window, SDL would take it for the game losing focus.
            Function defaultHandler = Function.getFunction("user32", "DefWindowProcW");
            boolean active = handle.equals(Function.getFunction("user32", "GetForegroundWindow").invokePointer(new Object[0]));
            defaultHandler.invokeLong(new Object[] {handle, WM_NCACTIVATE, 0L, 0L});
            defaultHandler.invokeLong(new Object[] {handle, WM_NCACTIVATE, active ? 1L : 0L, 0L});
        } catch (Throwable error) {
            LOGGER.warn("Couldn't give the window a dark title bar", error);
        }
    }

    private static int set(Function dwm, Pointer handle, int attribute, int value) {
        IntByReference data = new IntByReference(value);
        return dwm.invokeInt(new Object[] {handle, attribute, data.getPointer(), 4});
    }

    /** The client's icon, from small to large. */
    public static List<IoSupplier<InputStream>> icons() {
        List<IoSupplier<InputStream>> icons = new ArrayList<>();
        for (int size : new int[] {32, 64, 128, 256}) {
            String path = "/assets/cubeutilityclient/icons/icon_" + size + ".png";
            icons.add(() -> {
                InputStream stream = WindowStyle.class.getResourceAsStream(path);
                if (stream == null) {
                    throw new IOException("Missing " + path);
                }
                return stream;
            });
        }
        return icons;
    }
}

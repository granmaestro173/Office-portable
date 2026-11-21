package com.granmaestro.officeportable;

import java.util.Locale;

/** What the app knows about each file extension. */
final class FileTypes {
    private FileTypes() {}

    static final String[] OPENABLE_MIME_TYPES = {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.ms-word.document.macroEnabled.12",
            "application/msword",
            "application/vnd.oasis.opendocument.text",
            "application/rtf", "text/rtf", "text/plain",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.ms-excel.sheet.macroEnabled.12",
            "application/vnd.ms-excel",
            "application/vnd.oasis.opendocument.spreadsheet",
            "text/csv", "text/comma-separated-values",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation",
            "application/pdf",
            "application/octet-stream",
    };

    static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String baseName(String name) {
        if (name == null) return "Document";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Extension for a MIME type, used when a provider gives no file name. */
    static String extForMime(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.template": return "dotx";
            case "application/vnd.ms-word.document.macroEnabled.12": return "docm";
            case "application/msword": return "doc";
            case "application/vnd.oasis.opendocument.text": return "odt";
            case "application/rtf": case "text/rtf": return "rtf";
            case "text/plain": return "txt";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.template": return "xltx";
            case "application/vnd.ms-excel.sheet.macroEnabled.12": return "xlsm";
            case "application/vnd.ms-excel": return "xls";
            case "application/vnd.oasis.opendocument.spreadsheet": return "ods";
            case "text/csv": case "text/comma-separated-values": return "csv";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            case "application/vnd.openxmlformats-officedocument.presentationml.slideshow": return "ppsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.template": return "potx";
            case "application/vnd.ms-powerpoint": return "ppt";
            case "application/vnd.oasis.opendocument.presentation": return "odp";
            case "application/pdf": return "pdf";
            default: return "";
        }
    }

    static String mimeForExt(String ext) {
        switch (ext) {
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            case "csv": return "text/csv";
            case "txt": return "text/plain";
            case "rtf": return "application/rtf";
            case "pdf": return "application/pdf";
            default: return "application/octet-stream";
        }
    }

    /**
     * The extension the editor engine is told. Templates, slideshows and
     * macro-enabled variants share their base format's container.
     * Returns "" when the engine can't open the format.
     */
    static String editorExt(String ext) {
        switch (ext) {
            case "docx": case "doc": case "odt": case "rtf": case "txt":
            case "xlsx": case "xls": case "ods": case "csv":
            case "pptx": case "ppt": case "odp": case "pdf":
                return ext;
            case "dotx": case "docm": case "dotm": return "docx";
            case "dot": return "doc";
            case "ott": return "odt";
            case "xltx": case "xlsm": case "xltm": return "xlsx";
            case "xlt": return "xls";
            case "ots": return "ods";
            case "ppsx": case "potx": case "pptm": case "ppsm": case "potm": return "pptx";
            case "pps": case "pot": return "ppt";
            case "otp": return "odp";
            default: return "";
        }
    }

    /** "word", "cell", "slide", "pdf" or "" */
    static String kind(String ext) {
        switch (ext) {
            case "docx": case "dotx": case "docm": case "doc": case "dot": case "odt": case "ott":
            case "rtf": case "txt": case "md": case "htm": case "html": case "epub": case "fb2":
                return "word";
            case "xlsx": case "xltx": case "xlsm": case "xls": case "xlt": case "ods": case "ots": case "csv":
                return "cell";
            case "pptx": case "ppsx": case "potx": case "pptm": case "ppt": case "pps": case "pot": case "odp": case "otp":
                return "slide";
            case "pdf": case "xps": case "djvu": case "oxps":
                return "pdf";
            default:
                return "";
        }
    }

    /**
     * Formats the editor can write back in the same format, so "Save" can
     * overwrite the original file.
     */
    static boolean canSaveInPlace(String ext) {
        switch (ext) {
            case "docx": case "xlsx": case "pptx": case "csv":
            case "odt": case "ods": case "odp": case "rtf": case "txt": case "pdf":
                return true;
            default:
                return false;
        }
    }

    /** Best editable format to "Save as" when the original format can't be written. */
    static String modernExt(String ext) {
        switch (kind(ext)) {
            case "word": return "docx";
            case "cell": return "xlsx";
            case "slide": return "pptx";
            case "pdf": return "pdf";
            default: return "docx";
        }
    }

    static String badge(String ext) {
        switch (kind(ext)) {
            case "word": return "W";
            case "cell": return "X";
            case "slide": return "P";
            case "pdf": return "PDF";
            default: return "?";
        }
    }

    static int color(String ext) {
        switch (kind(ext)) {
            case "word": return 0xFF2B579A;
            case "cell": return 0xFF217346;
            case "slide": return 0xFFC43E1C;
            case "pdf": return 0xFFB30B00;
            default: return 0xFF5F6368;
        }
    }
}

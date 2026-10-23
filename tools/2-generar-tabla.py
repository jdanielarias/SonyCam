import io, json, os, sys

sys.stdout.reconfigure(encoding="utf-8")
here = os.path.dirname(os.path.abspath(__file__))

def expand(col):
    if col.endswith("(A)"):
        return [col[:-3], col[:-3] + "A"]
    return [col]

def build(path, has_page_key):
    d = json.load(io.open(here + "/" + path, encoding="utf-8"))
    rows = [r for r in d if r["code"][:4] in ("0X10", "0X92", "0X98", "0XC2", "0X40")]
    rows.sort(key=lambda r: int(r["code"], 16))
    models = list(rows[0]["models"].keys())
    assert all(list(r["models"].keys()) == models for r in rows), "column drift in " + path
    assert len(models) <= 63, "mask must fit a Long"
    idx = {}
    for i, col in enumerate(models):
        for n in expand(col):
            assert n not in idx, n
            idx[n] = i
    lines = []
    for r in rows:
        mask = 0
        for i, col in enumerate(models):
            if r["models"][col]:
                mask |= 1 << i
        if mask:
            lines.append('        "%s|%X|%s",' % (r["code"][2:], mask, " ".join(r["name"].split())))
    return idx, lines

v3_idx, v3_rows = build("compat.json", True)
v2_idx, v2_rows = build("compat2.json", False)

def kmap(idx, ind="            "):
    return "\n".join('%s"%s" to %d,' % (ind, m, i)
                     for m, i in sorted(idx.items(), key=lambda kv: (kv[1], kv[0])))

def klist(rows):
    return "\n".join(rows)

kt = '''package com.sonycam.monitor.sony

/**
 * Which operations and events Sony documents for each body it supports.
 *
 * Generated from the compatibility matrices in Sony's own references: *Camera
 * Control PTP 3* pages 87-109 for the 2020+ generation, and *Camera Control
 * PTP 2* pages 25-27 for the classic one. A body is looked up in the table for
 * the protocol version it actually negotiated, because the two references
 * describe two different command sets and several bodies appear in both.
 *
 * **This never decides what the app does.** The app negotiates from what the
 * camera itself advertises, and that stays the only source of truth: a table
 * baked into a build goes stale, and the camera in front of you does not. This
 * runs the comparison the other way — checking our handshake against Sony's
 * list, so a tester's transcript from a body the developer does not own says
 * "Sony documents 0x9211 here and we never saw it" instead of quietly running
 * at reduced capability.
 *
 * Device properties are deliberately excluded. A camera advertises only the
 * properties available in its current shooting mode, so comparing those would
 * report differences that are not faults.
 */
internal object SonyDocumentedCodes {

    /** Sony's protocol version words, as reported by SDIO_GetExtDeviceInfo. */
    const val PROTOCOL_2 = 0x00C8
    const val PROTOCOL_3 = 0x012C

    private class Table(models: Map<String, Int>, rows: List<String>) {
        val modelBits = models
        val byCode: Map<Int, Pair<Long, String>> = rows.associate { row ->
            val (code, mask, name) = row.split('|', limit = 3)
            code.toInt(16) to (mask.toLong(16) to name)
        }
    }

    private val v3 by lazy {
        Table(
            mapOf(
%s
            ),
            listOf(
%s
            ),
        )
    }

    private val v2 by lazy {
        Table(
            mapOf(
%s
            ),
            listOf(
%s
            ),
        )
    }

    private fun tableFor(protocolVersion: Int): Table? = when (protocolVersion) {
        PROTOCOL_3 -> v3
        PROTOCOL_2 -> v2
        else -> null
    }

    private fun bitFor(protocolVersion: Int, model: String): Pair<Table, Int>? {
        val t = tableFor(protocolVersion) ?: return null
        val bit = t.modelBits[model.trim().uppercase()] ?: return null
        return t to bit
    }

    /** Human-readable name Sony gives a code under this protocol version. */
    fun nameOf(protocolVersion: Int, code: Int): String? =
        tableFor(protocolVersion)?.byCode?.get(code)?.second

    /** True when Sony's table for that protocol has a column for this body. */
    fun knows(protocolVersion: Int, model: String): Boolean =
        bitFor(protocolVersion, model) != null

    /**
     * Codes Sony documents for this body that are missing from [advertised].
     *
     * Empty whenever the table cannot speak to the body, so an unrecognised
     * camera is never accused of a shortfall.
     */
    fun missingFor(
        protocolVersion: Int,
        model: String,
        advertised: Set<Int>,
    ): List<Pair<Int, String>> {
        val (t, bit) = bitFor(protocolVersion, model) ?: return emptyList()
        val flag = 1L shl bit
        return t.byCode.entries
            .filter { (code, v) -> (v.first and flag) != 0L && code !in advertised }
            .map { (code, v) -> code to v.second }
            .sortedBy { it.first }
    }

    /** Codes the body offered that Sony's table does not document for it. */
    fun undocumentedFor(
        protocolVersion: Int,
        model: String,
        advertised: Set<Int>,
    ): List<Int> {
        val (t, bit) = bitFor(protocolVersion, model) ?: return emptyList()
        val flag = 1L shl bit
        return advertised.filter { (t.byCode[it]?.first ?: 0L) and flag == 0L }.sorted()
    }
}
''' % (kmap(v3_idx), klist(v3_rows), kmap(v2_idx), klist(v2_rows))

target = "D:/Escritorio/SonyCam/app/src/main/java/com/sonycam/monitor/sony/SonyDocumentedCodes.kt"
io.open(target, "w", encoding="utf-8", newline="").write(kt)
print("v3: %d modelos, %d filas   v2: %d modelos, %d filas" %
      (len(v3_idx), len(v3_rows), len(v2_idx), len(v2_rows)))
print("escrito:", os.path.getsize(target), "bytes")

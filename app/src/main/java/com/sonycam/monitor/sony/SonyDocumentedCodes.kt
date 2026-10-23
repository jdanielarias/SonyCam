package com.sonycam.monitor.sony

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
            "ILCE-1M2" to 0,
            "ILCE-1" to 1,
            "ILCE-9M3" to 2,
            "ILCE-9M2" to 3,
            "ILCE-7RM5" to 4,
            "ILCE-7RM4A" to 5,
            "ILCE-7RM4" to 6,
            "ILCE-7M5" to 7,
            "ILCE-7M4" to 8,
            "ILCE-7SM3" to 9,
            "ILCE-7CM2" to 10,
            "ILCE-7CR" to 11,
            "ILCE-7C" to 12,
            "ILCE-6700" to 13,
            "ILX-LR1" to 14,
            "ILME-FX3" to 15,
            "ILME-FX3A" to 15,
            "ILME-FX30" to 16,
            "ILME-FX2" to 17,
            "ZV-E1" to 18,
            "ZV-E10" to 19,
            "ZV-E10M2" to 20,
            "ZV-1M2" to 21,
            "ZV-1" to 22,
            "ZV-1A" to 22,
            "ZV-1F" to 23,
            "DSC-RX1RM3" to 24,
            "DSC-RX100M7" to 25,
            "DSC-RX100M7A" to 25,
            "DSC-RX0M2" to 26,
            "ILME-FX6" to 27,
            "MPC-2610" to 28,
            "ILME-FR7" to 29,
            "BRC-AM7" to 30,
            "PXW-Z300" to 31,
            "PXW-Z200" to 32,
            "HXR-NX800" to 33,
            ),
            listOf(
        "1001|3FFFFFFFF|GetDeviceInfo",
        "1002|3FFFFFFFF|OpenSession",
        "1003|3FFFFFFFF|CloseSession",
        "1004|5B7FFB7|GetStorageIDs",
        "1005|5B7FFB7|GetStorageInfo",
        "1006|4B7FF37|GetNumObjects",
        "1007|5B7FFB7|GetObjectHandles",
        "1008|7FFFFFF|GetObjectInfo",
        "1009|7FFFFFF|GetObject",
        "100A|4B7FF37|GetThumb",
        "100D|117FFFF|SendObject",
        "101B|7FFFFFF|GetPartialObject",
        "4004|5B7FFB7|StoreAdded",
        "4005|5B7FFB7|StoreRemoved",
        "9201|3FFFFFFFF|SDIO Connect",
        "9202|3FFFFFFFF|SDIO GetExtDeviceInfo",
        "9205|3FFFFFFFF|SDIO SetExtDevicePropValue",
        "9207|3FFFFFFFF|SDIO ControlDevice",
        "9209|3FFFFFFFF|SDIO GetAllExtDevicePropInfo",
        "920F|3DF97|SDIO SetFTPSettingFilePassword",
        "9210|85B7FFB7|SDIO OpenSession",
        "9211|5B7FFB7|SDIO GetPartialLargeObject",
        "9212|5B7FFB7|SDIO SetContentsTransferMode",
        "9215|3F917EF97|SDIO GetDisplayStringList",
        "9216|3F9B7EF97|SDIO GetVendorCodeVersion",
        "9217|398000000|SDIO GetFTPJobList",
        "9218|398000000|SDIO ControlFTPJobList",
        "921A|38913CE85|SDIO UploadData",
        "921B|38913EF97|SDIO ControlUploadData",
        "921D|380000000|SDIO DownloadData",
        "921F|3CF97|SDIO GetFTPSettingList",
        "9220|3CF97|SDIO SetFTPSettingList",
        "9221|398A00000|SDIO ControlGeneralSettingFile",
        "9222|398A00000|SDIO GetControlGeneralSettingResultFile",
        "9223|1817CF97|SDIO GetLensInformation",
        "9227|380004390|SDIO RequestFirmwareUpdateCheck",
        "9228|380004390|SDIO SetFirmwareUpdateMode",
        "9229|380006397|SDIO UploadPartialData",
        "922A|380018000|SDIO ExecuteEFraming",
        "922F|3E117EF97|SDIO OperationResultsSupported",
        "9231|120287|SDIO GetPresetInfoList",
        "9233|3CF97|SDIO GetDisplayFTPResult",
        "9238|38107E397|SDIO GetOSDImage",
        "9239|6117A387|SDIO GetRestrictionInfo",
        "923A|107EF97|SDIO GetDeviceDescriptionFile",
        "923B|107EF97|SDIO GetCapturedDateList",
        "923C|107EF97|SDIO GetContentInfoList",
        "923D|107EF97|SDIO GetContentData",
        "923E|107EF97|SDIO GetContentCompressedData",
        "9241|380000000|SDIO GetStreamSettingList",
        "9242|380000000|SDIO SetStreamSettingList",
        "9245|60000000|SDIO ControlPTZF",
        "9246|60000000|SDIO SetPresetPTZF",
        "9247|380004390|SDIO GetFirmwareUpdateInfo",
        "9248|1026F97|SDIO GetAreaTimeZoneSetting",
        "9249|1026F97|SDIO SetAreaTimeZoneSetting",
        "924D|312|SDIO GetLicenseInfoList",
        "9250|6F97|SDIO DeleteContent",
        "9251|6F97|SDIO GetExtDeviceProp",
        "9803|5B7FFB7|GetObjectPropValue",
        "9805|5B7FFB7|GetObjectPropList",
        "C201|49FFF37|SDIE ObjectAdded",
        "C202|49FFF37|SDIE ObjectRemoved",
        "C203|3FFFFFFFF|SDIE DevicePropChanged",
        "C205|7FFFFFF|SDIE DateTimeSettingResult",
        "C206|777FFF7|SDIE CapturedEvent",
        "C208|1BFFFFF|SDIE CWBCapturedResult",
        "C209|117EFFF|SDIE CameraSettingReadResult",
        "C20A|3DF9F|SDIE FTPSettingReadResult",
        "C20B|38517FFBF|SDIE MediaFormatResult",
        "C20D|5B7FFB7|SDIE ContentsTransferEvent",
        "C20E|17EF97|SDIE ZoomandFocusPositionEvent",
        "C20F|3F917EF97|SDIE DisplayListChangedEvent",
        "C210|3F8000000|SDIE MediaProfileChanged",
        "C211|398000000|SDIE ControlJobListEvent",
        "C214|39917EF97|SDIE ControlUploadDataResult",
        "C217|4003E187|SDIE ZoomPositionResult",
        "C218|13EF97|SDIE FocusPositionResult",
        "C21A|398A00000|SDIE ControlGeneralSettingResult",
        "C21B|1817CF97|SDIE LensInformationChanged",
        "C21D|380004390|SDIE FirmwareUpdateCheckResult",
        "C21E|80004390|SDIE FirmwareUpdateEvent",
        "C222|3E117EF97|SDIE OperationResults",
        "C223|117EF97|SDIE AFStatus",
        "C224|117EF97|SDIE MovieRecOperationResults",
        "C226|120287|SDIE PresetInfoListChanged",
        "C228|117EF97|SDIE CautionDisplayEvent",
        "C234|107EF97|SDIE ContentInfoListChanged",
        "C238|60000000|SDIE ControlPTZFResult",
        "C239|60000000|SDIE PresetPTZFEvent",
        "C240|6F97|SDIE DeleteContentResult",
            ),
        )
    }

    private val v2 by lazy {
        Table(
            mapOf(
            "ILCE-1M2" to 0,
            "ILCE-1" to 1,
            "ILCE-9M3" to 2,
            "ILCE-9M2" to 3,
            "ILCE-7RM5" to 4,
            "ILCE-7RM4A" to 5,
            "ILCE-7RM4" to 6,
            "ILCE-7M4" to 7,
            "ILCE-7SM3" to 8,
            "ILCE-7RM3A" to 9,
            "ILCE-7M3" to 10,
            "ILCE-7M2" to 11,
            "ILCE-7CM2" to 12,
            "ILCE-7CR" to 13,
            "ILCE-7C" to 14,
            "ILCE-6700" to 15,
            "ILCE-6600" to 16,
            "ILCE-6400" to 17,
            "ILCE-6400A" to 17,
            "ILCE-6100" to 18,
            "ILCE-6100A" to 18,
            "ILX-LR1" to 19,
            "ILME-FX3" to 20,
            "ILME-FX3A" to 20,
            "ILME-FX30" to 21,
            "ILME-FX2" to 22,
            "ZV-E1" to 23,
            "ZV-E10" to 24,
            "ZV-E10M2" to 25,
            "ZV-1M2" to 26,
            "ZV-1F" to 27,
            "ZV-1" to 28,
            "ZV-1A" to 28,
            "DSC-RX0M2" to 29,
            "DSC-RX100M7" to 30,
            "DSC-RX100M7A" to 30,
            "DSC-RX100M5A" to 31,
            "DSC-RX10M4" to 32,
            "DSC-HX99" to 33,
            ),
            listOf(
        "1001|3FFFFFFFF|GetDeviceInfo",
        "1002|3FFFFFFFF|OpenSession",
        "1003|3FFFFFFFF|CloseSession",
        "1008|3FFFFFFFF|GetObjectInfo",
        "1009|3FFFFFFFF|GetObject",
        "100A|3FFFFFFFF|GetThumb",
        "101B|3FFFFFFFF|GetPartialObject",
        "9201|3FFFFFFFF|SDIO Connect",
        "9202|3FFFFFFFF|SDIO GetExtDeviceInfo",
        "9205|3FFFFFFFF|SDIO SetExtDevicePropValue",
        "9207|3FFFFFFFF|SDIO ControlDevice",
        "9209|3FFFFFFFF|SDIO GetAllExtDevicePropInfo",
        "C201|3FFFFFFFF|SDIE ObjectAdded",
        "C202|3FFFFFFFF|SDIE ObjectRemoved",
        "C203|3FFFFFFFF|SDIE DevicePropChanged",
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

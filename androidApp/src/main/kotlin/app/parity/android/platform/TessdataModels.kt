package app.parity.android.platform

/**
 * Tesseract models downloaded when a country's labels need them (design §14), pinned to the
 * tessdata_fast 4.1.0 release and checked against these SHA-256 digests before use. Georgian,
 * Russian and English ship in the app.
 */
internal object TessdataModels {
    const val BASE_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/"

    class Model(val bytes: Long, val sha256: String)

    val downloadable: Map<String, Model> = mapOf(
        "amh" to Model(5470094, "3ec3311833a108e07d58a1152b00c0cf1848752e4f85769d46e8ca2b718a2ccc"),
        "ara" to Model(1432056, "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729"),
        "asm" to Model(2045427, "299c7f6135ac72ca4820d4c39e3cf65b32b24127fc78c4938d11153adbb9fa77"),
        "bel" to Model(3692098, "9c6668a0b202f3dcfe074b64620d108e1902ca7498a40b5a11b4a3da6112d58f"),
        "ben" to Model(855841, "31163084c279aaebd376216f0c3d5c17ad4b5fee8db49dae79c20000b5de5964"),
        "bod" to Model(1966440, "1aab1db8dae337dfc8fa0f94a1c137c47e4c7857c3e6b30093ec726cef912a0b"),
        "bul" to Model(1675212, "aebc9b0fcc8cfaf8a9f38a02bb7b85052bd850744696a2c11cf0081820e5b21e"),
        "div" to Model(1774535, "06051412588963b34b663cc59a468dbf10753329f015ccd574ee711e8c6b84c3"),
        "dzo" to Model(449596, "364d244fad3f8d4438c9f7ee7642b9b876a4620cf76c0115619b979d6bfef1fa"),
        "ell" to Model(1419514, "4fba8a0b461038d51f1c20d043d4f2ac38c4e778f1b90830847f7bd8fa3ba726"),
        "fas" to Model(431500, "db1c0a91208aff00d3cf1ed2c1d23f76419afd5f024688b4f71adc3f2ce4a505"),
        "guj" to Model(1418394, "fa69658614b4946a9afae8853d67e0689838803dfa3d12c2e35ec53ee6f8df34"),
        "heb" to Model(961404, "11f9e43ab227f786352a50f75c94c2e9906f1baba86d93276da19da7ce0904db"),
        "hye" to Model(3463717, "b701d0d95799a716143dedb0197504e56f27a1bb133d6607ff5778c6988cb67c"),
        "kan" to Model(3608331, "bd31e6b6ae93271e3bcf5383d306d8eefbb91542937cd6d735a5930c970e61d8"),
        "kaz" to Model(4734644, "fcc01eed3815a42b9c6321c4c9d3606f39b166cbf95ade98b7d8d12063eae53d"),
        "khm" to Model(1446926, "47f110575341b322052f3becbefee61a3ecf1ef549352b5d4d33d28afe30d099"),
        "kir" to Model(9928497, "9777956300900b528d26932cf80693f95e75143433fb851d567194bcc38a31ae"),
        "lao" to Model(6386744, "20124962e93e68121e02c49a949d1f9df5db87dd62e5e4aa578362ca532444f8"),
        "mal" to Model(5275996, "bd05cbf1b197e7810d2903419aedb06f9ef77bfedf50b358673c1d18d707cdb4"),
        "mkd" to Model(1600188, "58622bf154830fa62103359938564aeb8112b929759e22a48224f3ecfaac34c6"),
        "mon" to Model(2137042, "a151a3806d61ac43619cd383896d551ba5c3b07388ffec6fc83c8c604d677570"),
        "mya" to Model(4640561, "02aa6c25cfe9e583fa7b5d4131eac948f962308983f0f397df077dea58212b03"),
        "ori" to Model(1480066, "36f3135e61d501a3acfad41f5fe60b8e791274fff4c5375c969fdcca980cdbac"),
        "pan" to Model(497721, "1ec0907fc3534065ea9ae190c6bb7ec9e5c74fd9d2fa996aaec7407f11ad8131"),
        "pus" to Model(1772087, "f15550bfe3a20ca781cf5afb7b2af6ee769c6521cf4bb990848e72856b484600"),
        "sin" to Model(1727413, "bc75c6df2375a30c9f7e759fa7d4b58ae3ecf9ce72668a702d01acb13e551422"),
        "srp" to Model(2149931, "aa41ae3d9cc705e60d398ab38a5c3cc8b772c0d420c7d4f0859beb13d0e321b6"),
        "tam" to Model(3237963, "d02fbec24be4b07e32e80d0ccfc3b6b67a3c5d61c9d0a7c8532677990912c6ec"),
        "tel" to Model(2769654, "d10691fddd5b67802e1c12800ebb321d3b8bcd8d24a2ac3ff206f93188c04ab5"),
        "tgk" to Model(2602685, "7b32ed1374649b9b44b9a20ed07f1e86c1e1004c3bd11ce182496a47a18cb321"),
        "tha" to Model(1072600, "294227cc2d1292b0acb28d61d4115c88252b96d466ca90b417cf4cf0c67bf07c"),
        "tir" to Model(378822, "73d7430c22a062b061f603a2c1d70c4ea4aa092f7a932a97921926e15b4fdb3d"),
        "uig" to Model(2794272, "163b360268c39fc69e0c0dc9299c06dfe7a6c51ef8f33766da75022217b69b22"),
        "ukr" to Model(3825102, "d59e53e2bded32f4445f124b4b00240fcac7e8044c003ab822ccb94f0b3db59b"),
        "urd" to Model(1398718, "62e8250ce2a994106e313a82e26a516a39e2cf159d0ce3c5b5008387fd0d555f"),
        "yid" to Model(545606, "f9a3d74076460a505dc0c6b59a0f5bd1108c6436d570954223b3695988d1666d"),
    )
}

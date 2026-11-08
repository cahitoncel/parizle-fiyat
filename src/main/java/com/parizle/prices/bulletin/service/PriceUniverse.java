package com.parizle.prices.bulletin.service;

import com.parizle.prices.bulletin.domain.AssetType;
import java.util.List;

/**
 * Describes which stored assets a price feed can price and how feed codes map to asset symbols.
 */
public enum PriceUniverse {

    BIST_STOCK(AssetType.STOCK, List.of("XIST"), false, SymbolFormat.SUFFIXED, ".E", List.of()),

    /**
     * VIOP instrument series are prefixed by instrument kind ({@code F_} future, {@code O_} option,
     * {@code TM_} underlying) while stored asset symbols usually omit that prefix.
     */
    BIST_FUTURE(AssetType.FUTURE, List.of("XIST"), true, SymbolFormat.PLAIN, null,
            List.of("F_", "O_", "TM_")),

    LONDON_STOCK(AssetType.STOCK, List.of("XLON"), false, SymbolFormat.SUFFIXED, ".L", List.of()),

    /**
     * NYSE and NASDAQ share Yahoo tickers without a suffix, so both markets form one universe.
     */
    US_STOCK(AssetType.STOCK, List.of("XNYS", "XNAS"), false, SymbolFormat.PLAIN, null, List.of()),

    /**
     * Yahoo lists Hong Kong stocks as four-digit zero-padded codes with a {@code .HK} suffix
     * ({@code 700} and {@code 0700.HK} both become {@code 0700.HK}).
     */
    HONG_KONG_STOCK(AssetType.STOCK, List.of("XHKG"), false, SymbolFormat.HONG_KONG, ".HK", List.of());

    private final AssetType assetType;
    private final List<String> markets;
    private final boolean includesNullMarket;
    private final SymbolFormat symbolFormat;
    private final String symbolSuffix;
    private final List<String> symbolPrefixes;

    PriceUniverse(AssetType assetType,
                  List<String> markets,
                  boolean includesNullMarket,
                  SymbolFormat symbolFormat,
                  String symbolSuffix,
                  List<String> symbolPrefixes) {
        this.assetType = assetType;
        this.markets = markets;
        this.includesNullMarket = includesNullMarket;
        this.symbolFormat = symbolFormat;
        this.symbolSuffix = symbolSuffix;
        this.symbolPrefixes = symbolPrefixes;
    }

    public AssetType assetType() {
        return assetType;
    }

    public List<String> markets() {
        return markets;
    }

    public boolean includesNullMarket() {
        return includesNullMarket;
    }

    public List<String> symbolPrefixes() {
        return symbolPrefixes;
    }

    /**
     * Maps a stored asset symbol to the code used by this feed.
     */
    public String toFeedSymbol(String symbol) {
        return switch (symbolFormat) {
            case PLAIN -> symbol;
            case SUFFIXED -> symbol.indexOf('.') >= 0 ? symbol : symbol + symbolSuffix;
            case HONG_KONG -> hongKongSymbol(symbol, symbolSuffix);
        };
    }

    private static String hongKongSymbol(String symbol, String suffix) {
        int dot = symbol.indexOf('.');
        String code = (dot >= 0 ? symbol.substring(0, dot) : symbol).trim();
        if (code.matches("\\d{1,4}")) {
            code = "0".repeat(4 - code.length()) + code;
        }
        return code + suffix;
    }

    static PriceUniverse forAssetType(AssetType assetType) {
        return assetType == AssetType.FUTURE ? BIST_FUTURE : BIST_STOCK;
    }

    private enum SymbolFormat {
        PLAIN,
        SUFFIXED,
        HONG_KONG
    }
}

package dev.phonecode.app.ui.theme

import androidx.compose.ui.graphics.Color

// PhoneCode design system - dark, single blue accent, OpenCode-grade. Tokens from the overhaul blueprint.
// White-alpha borders stay hierarchy-stable over any surface tier; surfaces form three solid elevation tiers.

// Surfaces / elevation
val PcBackground = Color(0xFF08090B)
val PcSurface = Color(0xFF121419)
val PcSurfaceRaised = Color(0xFF181B21)
val PcSurfaceInput = Color(0xFF1F232A)
val PcSurfaceOverlay = Color(0xFF23282F)
val PcCodeBg = Color(0xFF0C0E12)

// Borders / dividers (white over surface)
val PcBorderSubtle = Color(0x0FFFFFFF)
val PcBorder = Color(0x17FFFFFF)
val PcBorderStrong = Color(0x29FFFFFF)

// Text
val PcTextPrimary = Color(0xFFECEDEE)
val PcTextSecondary = Color(0xFF9BA1A8)
val PcTextFaint = Color(0xFF5C636B)

// Accent - the single signature color, reserved for actionable/selected state
val PcAccent = Color(0xFF4C8DFF)
val PcAccentSoft = Color(0x244C8DFF)
val PcAccentText = Color(0xFFA9C5FB)

// Status / semantics
val PcSuccess = Color(0xFF34D399)
val PcWarn = Color(0xFFF5A524)
val PcDanger = Color(0xFFF0566B)
val PcDangerSoft = Color(0x1AF0566B)

// Diff / code
val PcDiffAddBg = Color(0x1434D399)
val PcDiffDelBg = Color(0x14F0566B)
val PcCodeText = Color(0xFFD7D9DE)
val PcSyntaxKeyword = Color(0xFFC792EA)
val PcSyntaxString = Color(0xFF9ECE6A)
val PcSyntaxFunc = Color(0xFF7DCFFF)
val PcSyntaxComment = Color(0xFF5C6773)

/** Temporary deprecated alias - existing screens use this for "done"/success until re-skinned in Phase 1. */
val PcAccentTeal = PcSuccess

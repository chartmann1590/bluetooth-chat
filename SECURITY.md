# Security Policy

## Supported Versions

Only the latest release of MeshTalk (the most recent `build-N` tag / Play Store release) is supported with security fixes. There are no maintained older branches.

## Reporting a Vulnerability

If you find a security vulnerability in MeshTalk, please report it privately rather than opening a public issue:

- Preferred: use [GitHub's private vulnerability reporting](https://github.com/chartmann1590/bluetooth-chat/security/advisories/new) for this repository.
- Alternatively, open a regular GitHub issue with minimal detail and ask for a private channel to share the full report.

Please include:
- A description of the vulnerability and its potential impact
- Steps to reproduce (proof-of-concept code or a test case, if possible)
- The affected version/build number

We'll acknowledge reports within a few days and aim to ship a fix before any public disclosure. Please give us a reasonable window to patch before disclosing publicly.

## Scope

MeshTalk carries mesh chat traffic over Bluetooth Low Energy with no server in the loop for the `play` flavor, and a minimal Cloudflare Worker for entitlement verification in the `github` flavor. Vulnerabilities of particular interest:

- Cryptographic issues in message signing/encryption or identity key handling (`app/src/main/java/com/charles/meshtalk/app/crypto/`)
- BLE protocol issues that could allow spoofing, message forgery, or denial of service against the mesh
- Entitlement/billing bypass vulnerabilities
- Any issue that could leak a user's messages, identity, or location to an unintended party

Automated dependency vulnerability scanning (Dependabot) and static analysis (CodeQL) run continuously on this repository.

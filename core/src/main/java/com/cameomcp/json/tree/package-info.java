/**
 * A small JSON tree (objects, arrays, primitives, null) used by the Cameo-facing code to build
 * tool results. Its API follows the subset of the familiar Gson tree classes the plugin needs, so
 * the plugin runs on the JDK and Cameo alone, with no third-party JSON library.
 */
package com.cameomcp.json.tree;

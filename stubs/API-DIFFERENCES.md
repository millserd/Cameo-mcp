# Cameo OpenAPI: 2024x vs 2026x differences for the stubbed surface

Checked against https://jdocs.nomagic.com/2024x/ and https://jdocs.nomagic.com/2026x/ (October 2026).
Only classes and members present in `src/main/java` were compared. "Same" means the 2026x page shows
the same declaration, return type, parameters and throws clause.

At runtime only the erased descriptor matters (owner type as written in the call, name, erased
parameter types, erased return type). Generics, `@CheckForNull` and which supertype declares a
method do not. A missing member or a changed erased type causes `NoSuchMethodError` /
`NoSuchFieldError` / `NoClassDefFoundError` when that line runs.

## 1. Risky: present in 2024x javadoc, absent from 2026x javadoc

The 2026x javadoc for these classes omits members that the 2024x javadoc documents. The 2026x pages
for the presentation layer only show `@OpenApi` members, so the members may still exist but be
outside the documented OpenAPI, or they may be gone. Either way, treat them as unsupported on
2026x: call them through reflection or behind a capability check, and verify on a real 2026x install
before relying on them.

| Class | Member (2024x signature) | 2026x javadoc | Reference plugin uses it |
|---|---|---|---|
| `com.nomagic.magicdraw.uml.symbols.PresentationElement` | `public final PropertyManager getPropertyManager()` | not listed (not in declared or inherited members) | yes: DiagramHandler (`getPropertyManager().clone()`), PropertyDumpHandler, PresentationSerializer |
| `PresentationElement` | `public final DiagramPresentationElement getDiagramPresentationElement()` | not listed | no |
| `PresentationElement` | `public Property getProperty(String id)` (`com.nomagic.magicdraw.properties.Property`) | not listed | no |
| `com.nomagic.magicdraw.uml.symbols.AbstractDiagramPresentationElement` | `public boolean layout(boolean useCommands)` and `layout(boolean, DiagramLayouter)` | not listed | yes: DiagramHandler (`dpe.layout(...)`) |
| `AbstractDiagramPresentationElement` / `DiagramPresentationElement` | `isDiagramWindowOpen()`, `isDiagramWindowActive()` | not listed | yes: RelationMapHandler. **Also not found in the 2024x javadoc** (production code compiles against 2024x, so they exist there; stubbed as `boolean`, marked UNVERIFIED) |
| `com.nomagic.magicdraw.uml.symbols.layout` subpackages | 2024x has `layout.activity`, `layout.composite`, `layout.grid`, `layout.orderedhier`, `layout.state`, `layout.sysml2` | 2026x lists only `layout` and `layout.sequence` | no (only `DiagramLayouter` is stubbed) |
| `com.nomagic.magicdraw.core.Project` | `getID()` | missing in **both** versions; not stubbed | no |

`PresentationElementsManager.setPresentationElementProperties(PresentationElement, PropertyManager)`
is still documented in 2026x, so the reference's "clone the PropertyManager, edit, apply" pattern
needs a different way to get the `PropertyManager` if `getPropertyManager()` is really gone.

## 2. Moved or renamed types (no change to the stubbed signatures)

| Type | 2024x | 2026x | Impact |
|---|---|---|---|
| `MDElementImpl` (superclass of `PresentationElement`) | `com.nomagic.magicdraw.uml.MDElementImpl` | `com.nomagic.magicdraw.uml.core.impl.MDElementImpl` | Never reference `MDElementImpl` directly; call `getID()`/`getName()` through `PresentationElement` (or a subclass) so the call site resolves on both. The stub keeps the 2024x location. |
| `PathElement` interfaces | `com.nomagic.magicdraw.uml.symbols.LineJumpDrawer`, `...symbols.SuspendShapeAutoResizeModeProvider` | `com.dassault_systemes.modeler.foundation.diagram.symbols.LineJumpDrawer`, `...symbols.shapes.SuspendShapeAutoResizeModeProvider` | None unless you reference these interfaces. |
| `SwimlaneView` superclass | `ContainerShapeView` | `TableShapeView<ActivityPartition, SwimlaneCellView, SwimlaneHeaderView>` (which extends `ContainerShapeView`) | None. `createSwimlane` still returns `SwimlaneView`. |
| `ContainmentTree` superclass | `AbstractBrowserRootTabTree` -> `BrowserTabTree` -> `Tree` | `BrowserTabTree` -> `Tree` | None. `getSelectedNodes()` is still on `Tree` and returns `Node[]`. |

## 3. Deprecated members (deprecated in 2024x, still deprecated, not for removal, in 2026x)

The reference plugin uses the ones marked "used". Prefer the replacement in new code.

| Member | Replacement | Used by reference |
|---|---|---|
| `DiagramPresentationElement.getUsedModelElements(boolean)` (parameter ignored) | `getUsedModelElements()` | used (DiagramHandler) |
| `MatrixData.getValue(Element row, Element column)` | `getValue(ElementNode, ElementNode)` | used (MatrixHandler) |
| `FilterSettings.getScope()` | `getScopeRoots()` | used (MatrixHandler) |
| `ValidationRunData(Package, boolean wholeProject, Collection, EnumerationLiteral, boolean)` | `ValidationRunData(Package, Collection, EnumerationLiteral, boolean)` | used (ValidationHandler) |
| `ValidationRunData(Collection<Constraint>, String, boolean wholeProject, Collection, EnumerationLiteral, boolean)` | `ValidationRunData(Collection<Constraint>, String, Collection, EnumerationLiteral, boolean)` | used (ValidationHandler) |
| `Project.getModel()` | `getPrimaryModel()` / `getModels()` | no |
| `SessionManager.createSession(String)`, `closeSession()`, `cancelSession()`, `isSessionCreated()` | the `Project` overloads (the only ones stubbed) | no |
| `ProjectDescriptorsFactory.createProjectDescriptor(String)` | `createProjectDescriptor(URI)` (only the URI one is stubbed) | no |
| `SysMLProfile.BLOCK_STEREOTYPE` (and similar `*_STEREOTYPE` fields) | `SysMLProfile.BlockStereotype.STEREOTYPE_NAME` | no (not stubbed) |

The 2026x deprecated list has a "Terminally Deprecated" (for removal) section. Its only entries are
`ITeamworkService` methods, which are not stubbed.

## 4. Checked and the same in 2024x and 2026x

Declaration kind, superclass, return types, parameters and throws match for:

- `Application` (`getInstance`, `getProject`, `getGUILog`, `getMainFrame`, `getProjectsManager`,
  `addProjectEventListener` (final), `removeProjectEventListener`, `getEnvironmentOptions`,
  `environment()`), `Application.Environment` (`getInstallRoot`, `getDataDirectory`, `getConfigDir`,
  `getUserHomeDirectory`, `getUserDataDirectory`, `getTempDir`)
- `Project` (all stubbed members), `ProjectsManager`, `ProjectDescriptorsFactory`,
  `ProjectEventListener` (16 methods, 2 default), `ProjectEventListenerAdapter`
- `GUILog` (note: `showMessage(String, String)` takes a help ID, not a title; `showQuestion(String, boolean)` returns `int`)
- `SessionManager`, `ModelElementsManager` (all throw `ReadOnlyElementException`), `PresentationElementsManager`
- `DiagramPresentationElement` (`getDiagram`, `getDiagramType`, `getUsedModelElements` overloads),
  `AbstractDiagramPresentationElement` (`ensureLoaded`, `open`, `open(boolean)`, `close`),
  `PresentationElement` (`getBounds`, `getElement`, `getPresentationElements`, `getParent`, `isSelected`),
  `PathElement`, `DiagramType`
- `ImageExporter` (2026x adds `exportForResult(...)` and an SVG id-generator overload; existing overloads unchanged)
- `ClassTypes` (there is no `getAllClassTypes`; use `getAllClassNames()`), `Finder` and its nested finders
- `StereotypesHelper`, `TagsHelper`, `CoreHelper`, `ModelHelper` (`getComment`/`setComment`/`setMultiplicity`/
  `getMultiplicity` are declared on `CoreHelper` and inherited by `ModelHelper` in both versions)
- `ElementsFactory` (a class, not an interface, in both; all 49 stubbed `create*Instance` methods present)
- UML metamodel interfaces (`Element`, `NamedElement`, `Property`, `MultiplicityElement`, ...) and the
  enum classes `AggregationKindEnum`, `VisibilityKindEnum`, `ParameterDirectionKindEnum`,
  `PseudostateKindEnum` (final classes extending EMF `AbstractEnumerator`, not Java enums; their
  `NONE`/`COMPOSITE`/... fields are typed as the `*Kind` interface)
- `PropertyManager` (`clone()` returns `PropertyManager`), `com.nomagic.magicdraw.properties.Property`
- `Annotation`, `RuleViolationResult`, `ValidationHelper`, `ValidationRunData`
- Dependency matrix: `MatrixDataHelper`, `MatrixData`, `AbstractMatrixCell`, `DependencyEntry`,
  `PersistenceManager`, `FilterSettings`, `MatrixSettings` (+ `Direction`, `RelationOption` enums)
- `GenericTableManager`, `RelationshipMapUtilities`, `Connectors`, `CopyPasting`
- Actions: `ActionsConfiguratorsManager`, `AMConfigurator` (in `com.nomagic.actions`, not
  `com.nomagic.magicdraw.actions`), `ActionsManager`, `ActionsCategory`, `MDActionsCategory`,
  `NMAction`, `MDAction`
- `Plugin`, `PluginDescriptor`, `PluginUtils`
- `Browser`, `Tree`, `Node`, `MainFrame` (final, extends `JFrame` via JIDE classes, implements `ActionsGroups`)
- `SysMLConstants` (class still present, not deprecated), `SysMLProfile.BlockStereotype`

## 5. Not in the public javadoc of either version (stubbed from reference usage, marked `// UNVERIFIED`)

These come from plugin jars (`dependencymatrix_api.jar`, `relationshipmap_api.jar`) or internal
packages. The reference plugin compiles against them on 2024x, but nothing here says they exist or
are unchanged in 2026x. Load them reflectively or behind a capability probe.

- `com.nomagic.magicdraw.dependencymatrix.MatrixManager.getExistingOrCreate(Diagram, DiagramPresentationElement)` returning `DependencyMatrix`
- `com.nomagic.magicdraw.dependencymatrix.DependencyMatrix.setActive(boolean)`, `viewCreated()`, `viewDisposed()`
- `com.nomagic.magicdraw.visualization.relationshipmap.GraphUtils.isGraphSettingValid(GraphSettings)`
- `com.nomagic.magicdraw.visualization.relationshipmap.RelationMapManager.getRMDiagramComponent(DiagramPresentationElement)` (return type unknown; stubbed as `Object`)
- `com.nomagic.magicdraw.visualization.relationshipmap.model.settings.BasicGraphSettings` / `GraphSettings`
  (constructors and all getters/setters; `getDepth`/`setDepth` int vs Integer unknown;
  `INDEFINITE_DEPTH` value unknown and deliberately not a compile-time constant)
- `com.nomagic.magicdraw.expressions.specification.*` (`DSLRelationExpressionSpecification`,
  `CallExpressionSpecification`, `ExpressionSpecificationUtil`, `Direction`): the reference also loads these reflectively
- `AbstractDiagramPresentationElement.isDiagramWindowOpen()` / `isDiagramWindowActive()` (see section 1)
- `SysMLProfile.getInstance(BaseElement)` / `getInstanceByProject(Project)`: the method summary on the
  javadoc page was too long to read. Prefer the verified static `SysMLProfile.BlockStereotype.isInstance(Element)`.
- `com.nomagic.utils.PriorityProvider.HIGH_PRIORITY` / `MEDIUM_PRIORITY` / `LOW_PRIORITY`: values not
  shown in the javadoc; stubbed as non-constant fields so the real values are read at runtime.

## 6. Requested but not stubbed

| Requested | Finding |
|---|---|
| `com.nomagic.magicdraw.core.ApplicationEnvironment` | Not in the 2024x or 2026x javadoc. Use `Application.environment()` (static, returns `Application.Environment`) and `getInstallRoot()`. Neither version documents a log-file getter. |
| `com.nomagic.magicdraw.core.MDLog` (`getGeneralLog`, `getPluginsLog`) | Not in either javadoc, so the return type (log4j 1.x `org.apache.log4j.Logger` vs something else) cannot be verified. Not stubbed. Use `java.util.logging` (as the reference does) or `GUILog`. |
| `com.nomagic.magicdraw.plugins.PluginManager` | Does not exist in either version. `PluginUtils.getPlugins()` returns `Collection<Plugin>`. |
| `com.nomagic.magicdraw.copypaste.CopyPasting` | Exists, unchanged; stubbed. |
| `ClassTypes.getAllClassTypes()` | Does not exist; `getAllClassNames()` (returns `Collection<String>`) is stubbed. |
| `Project.getID()` / `Project.getCounter()` | Not documented in either version; not stubbed. |
| `com.nomagic.magicdraw.sysml.util.SysMLProfile` | Present in both (extends `com.nomagic.profiles.ProfileImplementation`); stubbed minimally. |
| `com.nomagic.runtime.ApplicationExitedException` | Same in both (`extends Exception`, ctor `(byte)`); stubbed. |
| `TagsHelper` | Superclass of `StereotypesHelper` and declares the tagged-value methods; stubbed. |

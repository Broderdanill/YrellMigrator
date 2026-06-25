package se.yrell.migrator.ui.dialogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.bmc.ObjectTypeRegistry;
import se.yrell.migrator.core.DiffDetail;
import se.yrell.migrator.core.DiffIgnoreAdvisor;
import se.yrell.migrator.core.DiffIgnoreAdvisor.IgnoreSuggestion;
import se.yrell.migrator.core.DiffSummaryAnalyzer;

/** Shows Yrell Migrator's structured diff details in a user-oriented report layout. */
public final class DifferenceDetailsDialog extends TitleAreaDialog {
    private static final String ALL_SECTIONS = "All changes";

    private final CompareResult result;
    private final List<DiffDetail> allDetails;
    private TableViewer navigator;
    private TableViewer detailViewer;
    private Text sourceValue;
    private Text targetValue;
    private Text deltaValue;
    private Text selectedSummary;
    private Text reportSummary;
    private DiffDetail selectedDetail;
    private Button copyShortIgnoreButton;
    private Button copyScopedIgnoreButton;
    private Button copyPropertyLineButton;
    private Button copySelectedChangeButton;
    private String activeSection = ALL_SECTIONS;

    public DifferenceDetailsDialog(Shell parentShell, CompareResult result) {
        super(parentShell);
        this.result = result;
        this.allDetails = simplify(new ArrayList<DiffDetail>(result.getDifferenceDetails()));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle(result.getObjectType() + " — " + result.getObjectName());
        setMessage(result.getSourceServer() + " → " + result.getTargetServer() + "  |  " + result.getStatus().getLabel());

        Composite root = new Composite(area, SWT.NONE);
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        root.setLayout(new GridLayout(1, false));

        createSummary(root);
        createBody(root);
        return area;
    }

    private void createSummary(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Difference summary");
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        group.setLayout(new GridLayout(4, false));

        addReadOnly(group, "Object type", result.getObjectType());
        addReadOnly(group, "Object name", result.getObjectName());
        addReadOnly(group, "Source", result.getSourceServer());
        addReadOnly(group, "Target", result.getTargetServer());
        addReadOnly(group, "Status", result.getStatus().getLabel());
        addReadOnly(group, "Evidence", result.getEvidenceLabel());
        addReadOnly(group, "Customization", result.getCustomizationTypeSummary());
        addReadOnly(group, "Cache policy", ObjectTypeRegistry.cachePolicyFor(result.getObjectType(), result.getCustomizationTypeSummary()));
        addReadOnly(group, "Evidence detail", result.getEvidenceDetail());

        Label label = new Label(group, SWT.NONE);
        label.setText("What changed");
        reportSummary = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
        data.heightHint = 54;
        reportSummary.setLayoutData(data);
        reportSummary.setText(summaryLine());
    }

    private void addReadOnly(Composite parent, String labelText, String value) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);
        Text text = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setText(value == null ? "" : value);
    }

    private void createBody(Composite parent) {
        SashForm horizontal = new SashForm(parent, SWT.HORIZONTAL);
        horizontal.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createNavigator(horizontal);
        createDetailsPanel(horizontal);
        horizontal.setWeights(new int[] { 23, 77 });
    }

    private void createNavigator(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Sections");
        group.setLayout(new GridLayout(1, false));

        navigator = new TableViewer(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        Table table = navigator.getTable();
        table.setHeaderVisible(false);
        table.setLinesVisible(false);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        navigator.setContentProvider(ArrayContentProvider.getInstance());
        TableViewerColumn column = new TableViewerColumn(navigator, SWT.NONE);
        column.getColumn().setWidth(300);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return element == null ? "" : ((SectionNode) element).label;
            }

            @Override
            public Image getImage(Object element) {
                return imageForSection((SectionNode) element);
            }
        });
        final List<SectionNode> nodes = sectionNodes();
        navigator.setInput(nodes);
        navigator.addSelectionChangedListener(new ISelectionChangedListener() {
            public void selectionChanged(SelectionChangedEvent event) {
                IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                SectionNode node = selection == null ? null : (SectionNode) selection.getFirstElement();
                activeSection = node == null ? ALL_SECTIONS : node.section;
                refreshDetailTable();
            }
        });
        if (!nodes.isEmpty()) {
            navigator.setSelection(new StructuredSelection(nodes.get(0)), true);
        }
    }

    private void createDetailsPanel(Composite parent) {
        SashForm vertical = new SashForm(parent, SWT.VERTICAL);

        detailViewer = new TableViewer(vertical, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | SWT.SINGLE);
        Table table = detailViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        detailViewer.setContentProvider(ArrayContentProvider.getInstance());

        addColumn("Change", 135, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return prettyKind(((DiffDetail) element).getKind());
            }
            @Override
            public Image getImage(Object element) {
                return imageFor((DiffDetail) element);
            }
        });
        addColumn("Section", 140, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return logicalSection((DiffDetail) element);
            }
        });
        addColumn("Item", 260, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return itemName((DiffDetail) element);
            }
        });
        addColumn("Property", 210, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return prettifyProperty(((DiffDetail) element).getProperty());
            }
        });
        addColumn("Source value", 330, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return oneLine(friendlyValue((DiffDetail) element, true));
            }
        });
        addColumn("Target value", 330, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return oneLine(friendlyValue((DiffDetail) element, false));
            }
        });

        Group selected = new Group(vertical, SWT.NONE);
        selected.setText("Selected change");
        selected.setLayout(new GridLayout(3, true));

        selectedSummary = new Text(selected, SWT.BORDER | SWT.READ_ONLY);
        selectedSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        createValueBlock(selected, "Source: " + result.getSourceServer(), true);
        createValueBlock(selected, "Target: " + result.getTargetServer(), false);
        createDeltaBlock(selected);
        createSelectedButtons(selected);

        detailViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            public void selectionChanged(SelectionChangedEvent event) {
                IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                updateSelected(selection == null ? null : (DiffDetail) selection.getFirstElement());
            }
        });

        refreshDetailTable();
        vertical.setWeights(new int[] { 57, 43 });
    }

    private void createValueBlock(Composite parent, String title, boolean source) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        group.setLayout(new GridLayout(1, false));
        Text text = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        if (source) {
            sourceValue = text;
        } else {
            targetValue = text;
        }
    }

    private void createDeltaBlock(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Plain-language difference");
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        group.setLayout(new GridLayout(1, false));
        deltaValue = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        deltaValue.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    private void createSelectedButtons(Composite parent) {
        Composite buttons = new Composite(parent, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        buttons.setLayout(new GridLayout(4, false));

        copyShortIgnoreButton = new Button(buttons, SWT.PUSH);
        copyShortIgnoreButton.setText("Copy ignore token");
        copyShortIgnoreButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copyShortIgnoreButton.addListener(SWT.Selection, event -> copyIgnoreToken(false));

        copyScopedIgnoreButton = new Button(buttons, SWT.PUSH);
        copyScopedIgnoreButton.setText("Copy scoped ignore");
        copyScopedIgnoreButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copyScopedIgnoreButton.addListener(SWT.Selection, event -> copyIgnoreToken(true));

        copyPropertyLineButton = new Button(buttons, SWT.PUSH);
        copyPropertyLineButton.setText("Copy property line");
        copyPropertyLineButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copyPropertyLineButton.addListener(SWT.Selection, event -> copyIgnorePropertyLine());

        copySelectedChangeButton = new Button(buttons, SWT.PUSH);
        copySelectedChangeButton.setText("Copy selected change");
        copySelectedChangeButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copySelectedChangeButton.addListener(SWT.Selection, event -> copySelectedChange());
        updateCopyButtons(null);
    }

    private void addColumn(String title, int width, ColumnLabelProvider provider) {
        TableViewerColumn column = new TableViewerColumn(detailViewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.setLabelProvider(provider);
    }

    private void refreshDetailTable() {
        if (detailViewer == null) {
            return;
        }
        List<DiffDetail> filtered = filteredDetails();
        detailViewer.setInput(filtered);
        if (filtered.isEmpty()) {
            selectedSummary.setText("No detailed rows for this section.");
            sourceValue.setText(result.getDetail());
            targetValue.setText("");
            if (deltaValue != null) {
                deltaValue.setText("");
            }
        } else {
            detailViewer.setSelection(new StructuredSelection(filtered.get(0)), true);
            updateSelected(filtered.get(0));
        }
    }

    private List<DiffDetail> filteredDetails() {
        if (ALL_SECTIONS.equals(activeSection)) {
            return new ArrayList<DiffDetail>(allDetails);
        }
        List<DiffDetail> out = new ArrayList<DiffDetail>();
        for (DiffDetail detail : allDetails) {
            if (activeSection.equals(logicalSection(detail))) {
                out.add(detail);
            }
        }
        return out;
    }

    private List<SectionNode> sectionNodes() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put(ALL_SECTIONS, Integer.valueOf(allDetails.size()));
        List<DiffDetail> sorted = new ArrayList<DiffDetail>(allDetails);
        Collections.sort(sorted, new Comparator<DiffDetail>() {
            public int compare(DiffDetail left, DiffDetail right) {
                int section = sectionSortWeight(logicalSection(left)) - sectionSortWeight(logicalSection(right));
                if (section != 0) {
                    return section;
                }
                int item = String.CASE_INSENSITIVE_ORDER.compare(itemName(left), itemName(right));
                if (item != 0) {
                    return item;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(prettifyProperty(left.getProperty()), prettifyProperty(right.getProperty()));
            }
        });
        for (DiffDetail detail : sorted) {
            String section = logicalSection(detail);
            Integer old = counts.get(section);
            counts.put(section, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }
        List<SectionNode> nodes = new ArrayList<SectionNode>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            nodes.add(new SectionNode(entry.getKey(), entry.getKey() + " (" + entry.getValue() + ")"));
        }
        return nodes;
    }

    private void updateSelected(DiffDetail detail) {
        selectedDetail = detail;
        updateCopyButtons(detail);
        if (detail == null) {
            return;
        }
        String item = itemName(detail);
        StringBuilder title = new StringBuilder();
        title.append(prettyKind(detail.getKind())).append(" — ").append(logicalSection(detail));
        if (item.length() > 0) {
            title.append(" / ").append(item);
        }
        String property = prettifyProperty(detail.getProperty());
        if (property.length() > 0) {
            title.append(" / ").append(property);
        }
        selectedSummary.setText(title.toString());
        sourceValue.setText(friendlyValue(detail, true));
        targetValue.setText(friendlyValue(detail, false));
        if (deltaValue != null) {
            deltaValue.setText(buildDeltaText(detail));
        }
    }

    private String buildDeltaText(DiffDetail detail) {
        String source = detail == null ? "" : friendlyValue(detail, true);
        String target = detail == null ? "" : friendlyValue(detail, false);
        String property = detail == null ? "Value" : prettifyProperty(detail.getProperty());
        if (source.length() == 0 && target.length() > 0) {
            return property + " exists only in target.\n\nTarget value:\n" + target + "\n\n" + excludeHint(detail);
        }
        if (source.length() > 0 && target.length() == 0) {
            return property + " exists only in source.\n\nSource value:\n" + source + "\n\n" + excludeHint(detail);
        }
        if (source.equals(target)) {
            return "No textual difference in the selected value.";
        }
        String[] a = splitForDiff(source);
        String[] b = splitForDiff(target);
        StringBuilder out = new StringBuilder();
        out.append(property).append(" is different.\n");
        out.append("Lines starting with '-' are source. Lines starting with '+' are target.\n\n");
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max && i < 140; i++) {
            String av = i < a.length ? a[i] : "";
            String bv = i < b.length ? b[i] : "";
            if (av.equals(bv)) {
                out.append("  ").append(av).append('\n');
            } else {
                if (av.length() > 0) {
                    out.append("- ").append(av).append('\n');
                }
                if (bv.length() > 0) {
                    out.append("+ ").append(bv).append('\n');
                }
            }
        }
        if (max > 140) {
            out.append("... truncated after 140 comparison lines ...\n");
        }
        out.append("\n").append(excludeHint(detail));
        return out.toString();
    }

    private String excludeHint(DiffDetail detail) {
        return DiffIgnoreAdvisor.longHelp(detail);
    }

    private void updateCopyButtons(DiffDetail detail) {
        IgnoreSuggestion suggestion = DiffIgnoreAdvisor.suggest(detail);
        boolean hasSelection = detail != null;
        boolean hasToken = hasSelection && suggestion.hasToken();
        if (copyShortIgnoreButton != null && !copyShortIgnoreButton.isDisposed()) {
            copyShortIgnoreButton.setEnabled(hasToken);
            copyShortIgnoreButton.setToolTipText(hasToken ? suggestion.getShortToken() : "No stable ignore token for the selected row.");
        }
        if (copyScopedIgnoreButton != null && !copyScopedIgnoreButton.isDisposed()) {
            boolean hasScoped = hasToken && suggestion.getScopedToken().length() > 0 && !suggestion.getScopedToken().equals(suggestion.getShortToken());
            copyScopedIgnoreButton.setEnabled(hasScoped);
            copyScopedIgnoreButton.setToolTipText(hasScoped ? suggestion.getScopedToken() : "No separate scoped ignore token for the selected row.");
        }
        if (copyPropertyLineButton != null && !copyPropertyLineButton.isDisposed()) {
            boolean hasLine = hasSelection && suggestion.getPropertyLine().length() > 0;
            copyPropertyLineButton.setEnabled(hasLine);
            copyPropertyLineButton.setToolTipText(hasLine ? suggestion.getPropertyLine() : "No property-file line for the selected row.");
        }
        if (copySelectedChangeButton != null && !copySelectedChangeButton.isDisposed()) {
            copySelectedChangeButton.setEnabled(hasSelection);
        }
    }

    private void copyIgnoreToken(boolean scoped) {
        IgnoreSuggestion suggestion = DiffIgnoreAdvisor.suggest(selectedDetail);
        String text = scoped ? suggestion.getScopedToken() : suggestion.getShortToken();
        copyToClipboard(text);
    }

    private void copyIgnorePropertyLine() {
        IgnoreSuggestion suggestion = DiffIgnoreAdvisor.suggest(selectedDetail);
        copyToClipboard(suggestion.getPropertyLine());
    }

    private void copySelectedChange() {
        if (selectedDetail == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append(result.getObjectType()).append(' ').append(result.getObjectName()).append('\n');
        out.append("Section: ").append(logicalSection(selectedDetail)).append('\n');
        out.append("Change: ").append(prettyKind(selectedDetail.getKind())).append('\n');
        out.append("Item: ").append(itemName(selectedDetail)).append('\n');
        out.append("Property: ").append(prettifyProperty(selectedDetail.getProperty())).append('\n');
        out.append("Source: ").append(friendlyValue(selectedDetail, true)).append('\n');
        out.append("Target: ").append(friendlyValue(selectedDetail, false)).append('\n');
        IgnoreSuggestion suggestion = DiffIgnoreAdvisor.suggest(selectedDetail);
        if (suggestion.hasToken()) {
            out.append("Ignore token: ").append(suggestion.getShortToken()).append('\n');
            if (suggestion.getScopedToken().length() > 0 && !suggestion.getScopedToken().equals(suggestion.getShortToken())) {
                out.append("Scoped ignore token: ").append(suggestion.getScopedToken()).append('\n');
            }
            if (suggestion.getPropertyLine().length() > 0) {
                out.append("Property-file line: ").append(suggestion.getPropertyLine()).append('\n');
            }
        }
        copyToClipboard(out.toString());
    }

    private void copyToClipboard(String text) {
        if (text == null || text.length() == 0 || getShell() == null || getShell().isDisposed()) {
            return;
        }
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private String friendlyValue(DiffDetail detail, boolean source) {
        if (detail == null) {
            return "";
        }
        return friendlyValue(source ? detail.getSourceValue() : detail.getTargetValue());
    }

    private String friendlyValue(String value) {
        String text = nullToEmpty(value).trim();
        if (text.length() == 0) {
            return "";
        }
        text = text.replaceAll("com\\.bmc\\.arsys\\.[A-Za-z0-9_.$]+@([0-9a-fA-F]+)", "Internal Developer Studio object");
        text = text.replaceAll("com\\.bmc\\.[A-Za-z0-9_.$]+@([0-9a-fA-F]+)", "Internal Developer Studio object");
        text = text.replaceAll("[A-Za-z0-9_.$]+@[0-9a-fA-F]{4,}", "Internal Java object");
        text = text.replace("\r", "\n");
        text = text.replace("\t", " ");
        text = text.replace("{", "{\n  ").replace("}", "\n}");
        text = text.replace(", ", ",\n  ");
        return text.trim();
    }

    private String[] splitForDiff(String value) {
        if (value == null || value.length() == 0) {
            return new String[0];
        }
        String normalized = value.replace("],", "],\n")
                .replace("},", "},\n")
                .replace(";", ";\n")
                .replace(", ", ",\n");
        return normalized.split("\\r?\\n");
    }

    private String summaryLine() {
        return DiffSummaryAnalyzer.operatorSummary(result, 7);
    }

    private List<DiffDetail> simplify(List<DiffDetail> details) {
        if (details == null || details.isEmpty()) {
            return new ArrayList<DiffDetail>();
        }
        List<DiffDetail> cleaned = new ArrayList<DiffDetail>();
        for (DiffDetail detail : details) {
            if (detail == null || isVisualNoise(detail)) {
                continue;
            }
            cleaned.add(detail);
        }
        Collections.sort(cleaned, new Comparator<DiffDetail>() {
            public int compare(DiffDetail left, DiffDetail right) {
                int section = sectionSortWeight(logicalSection(left)) - sectionSortWeight(logicalSection(right));
                if (section != 0) return section;
                int item = String.CASE_INSENSITIVE_ORDER.compare(itemName(left), itemName(right));
                if (item != 0) return item;
                return String.CASE_INSENSITIVE_ORDER.compare(prettifyProperty(left.getProperty()), prettifyProperty(right.getProperty()));
            }
        });
        return cleaned;
    }

    private boolean isVisualNoise(DiffDetail detail) {
        String property = nullToEmpty(detail.getProperty()).toLowerCase();
        String area = nullToEmpty(detail.getArea()).toLowerCase();
        if (property.length() == 0) {
            return false;
        }
        if ("cache mode".equals(property) || "last update time".equals(property) || property.indexOf("supportedmask") >= 0) {
            return true;
        }
        if ("definition".equals(property) && (area.indexOf("main settings") >= 0 || area.indexOf("object") >= 0)) {
            return true;
        }
        if (property.indexOf("listener") >= 0 || property.indexOf("adapter") >= 0 || property.indexOf("cache") >= 0) {
            return true;
        }
        return false;
    }

    private String logicalSection(DiffDetail detail) {
        String area = detail == null ? "" : nullToEmpty(detail.getArea());
        String property = detail == null ? "" : nullToEmpty(detail.getProperty());
        String lowerArea = area.toLowerCase();
        String lowerProperty = property.toLowerCase();
        return DiffSummaryAnalyzer.logicalSection(detail);
    }

    private int sectionSortWeight(String section) {
        return DiffSummaryAnalyzer.sectionSortWeight(section);
    }

    private String itemName(DiffDetail detail) {
        String area = detail == null ? "" : nullToEmpty(detail.getArea()).trim();
        if (area.length() == 0) {
            return "";
        }
        String[] prefixes = new String[] {
                "Field ", "View ", "If Action ", "Else Action ", "Menu Item ", "Index ", "Permission ", "Guide Membership " };
        for (int i = 0; i < prefixes.length; i++) {
            if (area.startsWith(prefixes[i])) {
                return area.substring(prefixes[i].length()).trim();
            }
        }
        int slash = area.indexOf('/');
        if (slash >= 0 && slash + 1 < area.length()) {
            return area.substring(slash + 1).trim();
        }
        if ("Main Settings".equals(area) || "Execution".equals(area) || "Qualification".equals(area) || "Associated Forms".equals(area)) {
            return "";
        }
        return area;
    }

    private String prettyKind(String kind) {
        String value = kind == null || kind.trim().length() == 0 ? "property" : kind.trim();
        String lower = value.toLowerCase();
        if (lower.indexOf("missing") >= 0) {
            return lower.indexOf("source") >= 0 ? "Only in target" : lower.indexOf("target") >= 0 ? "Only in source" : "Missing";
        }
        if (lower.indexOf("error") >= 0) return "Error";
        if (lower.indexOf("audit") >= 0) return "Audit changed";
        if (lower.indexOf("qualification") >= 0) return "Qualification changed";
        if (lower.indexOf("action") >= 0) return "Action changed";
        if (lower.indexOf("permission") >= 0) return "Permission changed";
        if (lower.indexOf("field") >= 0) return "Field changed";
        if (lower.indexOf("view") >= 0) return "View changed";
        if (lower.indexOf("binary") >= 0 || lower.indexOf("fingerprint") >= 0) return "Binary changed";
        return "Changed";
    }

    private String prettifyProperty(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        if ("definition".equalsIgnoreCase(value)) {
            return "Definition";
        }
        if (value.startsWith("mask ")) {
            return "Object mask " + value.substring(5);
        }
        String text = value.replace('_', ' ').replace(".", " / ");
        if ("runIfQualification".equals(value)) return "Run If qualification";
        if ("elseIfQualification".equals(value)) return "Else If qualification";
        if ("fieldId".equals(value) || "fieldID".equals(value)) return "Field ID";
        if ("dataType".equals(value)) return "Data type";
        if ("fieldType".equals(value)) return "Field type";
        if ("executeOn".equals(value)) return "Execute on";
        if ("executionOrder".equals(value)) return "Execution order";
        if ("customizationType".equals(value)) return "Customization type";
        if ("overlayType".equals(value)) return "Overlay type";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(text.charAt(i - 1))) {
                out.append(' ');
            }
            out.append(c);
        }
        return out.length() == 0 ? value : Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    private String oneLine(String value) {
        String text = nullToEmpty(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        int max = 120;
        return text.length() > max ? text.substring(0, max) + " ..." : text;
    }

    private Image imageForSection(SectionNode node) {
        if (node == null || ALL_SECTIONS.equals(node.section)) {
            return shared(ISharedImages.IMG_OBJ_FOLDER);
        }
        String section = node.section == null ? "" : node.section;
        if ("Permissions".equals(section) || "Audit Settings".equals(section) || "Binary".equals(section)) {
            return shared(ISharedImages.IMG_OBJS_WARN_TSK);
        }
        if ("Fields".equals(section) || "Views".equals(section) || "If Actions".equals(section) || "Else Actions".equals(section)) {
            return shared(ISharedImages.IMG_OBJ_ELEMENT);
        }
        return shared(ISharedImages.IMG_OBJ_FILE);
    }

    private Image imageFor(DiffDetail detail) {
        String kind = detail == null ? "" : detail.getKind().toLowerCase();
        if (kind.indexOf("error") >= 0) return shared(ISharedImages.IMG_OBJS_ERROR_TSK);
        if (kind.indexOf("missing") >= 0) return shared(ISharedImages.IMG_OBJS_WARN_TSK);
        return shared(ISharedImages.IMG_OBJ_FILE);
    }

    private Image shared(String key) {
        try {
            return PlatformUI.getWorkbench().getSharedImages().getImage(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1380, 840);
    }

    private static final class SectionNode {
        final String section;
        final String label;
        SectionNode(String section, String label) {
            this.section = section;
            this.label = label;
        }
    }
}

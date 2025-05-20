// @ExecutionModes({ON_SINGLE_NODE})

/* 
 * Freeplane Dynamic Types Creator
 *
 * v0.8.2
 * 
 * Info & Discussion: https://github.com/freeplane/freeplane/discussions/2365
 *
 * Based on the "Templates Chooser" script by Quinbus and adxsoft : https://docs.freeplane.org/scripting/Scripts_collection.html
 * Extended by euu2021 and i-plasm
 *
 * ---------
 *
 * Freeplane Dynamic Types Creator: Freeplane utility for creating dynamic node templates and forms.
 *
 *
 * Copyright (C) 2024 The Authors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

import groovy.swing.SwingBuilder
import java.awt.Color
import java.awt.FlowLayout as FL
import java.awt.event.FocusListener
import javax.swing.BoxLayout as BXL
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.JComboBox
import javax.swing.*

import javax.swing.JOptionPane
import java.util.regex.Pattern
import java.util.regex.Matcher
import org.freeplane.core.resources.ResourceController
import org.freeplane.core.util.ConfigurationUtils
import org.freeplane.core.util.LogUtils
import org.freeplane.core.util.TextUtils
import org.freeplane.plugin.script.ScriptResources
import org.freeplane.plugin.script.proxy.ScriptUtils
import groovy.transform.Field


def c = ScriptUtils.c()
def node = ScriptUtils.node()

// Constants
final String TEMPLATE_PREFIX = "Template:"
final String SETTINGS_NODE_PREFIX = "Template Settings"
final String MASTERLIST_PREFIX = "List:"
final String MACROGROUP_PREFIX = "MacroGroup:"
final String HEADERNODE_SETTING_PREFIX = "Header:"
final String MULTISELECT_ITEMS_SEPARATOR = ";"

// @Fields
@Field def namedMacros
@Field final String MACROERROR_PREFIX = "MacroError:"
@Field def header


// --- Stage 0: Preliminary Preparations ---

// --- Preparar diretórios de scripts para {{/myScriptName}} ---
final ResourceController rc = ResourceController.getResourceController()
final String dirsString = rc.getProperty(ScriptResources.RESOURCES_SCRIPT_DIRECTORIES)
 scriptDirs = new TreeSet<File>()
if (dirsString) {
    for (String path : ConfigurationUtils.decodeListValue(dirsString, false)) {
        scriptDirs << new File(path)
    }
}
scriptDirs << ScriptResources.getBuiltinScriptsDir()
scriptDirs << ScriptResources.getUserScriptsDir()

// Gather named macros: {{myNamedMacro}}
namedMacros = [:]
node.mindMap.root.find { it.plainText.trim().startsWith(MACROGROUP_PREFIX) }.each {
   it.children.forEach() {
      def trimmedPlainText = it.plainText.trim()
      if (trimmedPlainText.startsWith("{{") && trimmedPlainText.endsWith("}}"))
         namedMacros[trimmedPlainText.substring(2, trimmedPlainText.length() - 2)] = it.note? it.note.plain : null
   }
}

// --- Stage 1: Find Templates and MasterLists, and Show Template Picker ---
def masterLists = [:]
node.mindMap.root.find { it.plainText.trim().startsWith(MASTERLIST_PREFIX) }.each {
    masterLists[it.plainText.trim()] = it.children.collect {item ->  item.plainText.trim() }
}

// Templates mapping [templatename_without_prefix:node]
def templateHashMap = [:]
node.mindMap.root.find { it.plainText.startsWith(TEMPLATE_PREFIX) }.each {
    templateHashMap[it.plainText.substring(TEMPLATE_PREFIX.length()).trim()] = it
}

def templateList = templateHashMap.keySet() as ArrayList

if (templateHashMap.isEmpty()) {
    JOptionPane.showMessageDialog(ui.frame, "No nodes found starting with '${TEMPLATE_PREFIX}'.", "Template Picker Error", JOptionPane.ERROR_MESSAGE);
    return
}

def s = new SwingBuilder()
s.setVariable('templateDialog-properties', [:])
def templateVars = s.variables
def templateDialog = s.dialog(title: 'Template Picker', id: 'templateDialog', minimumSize: [300, 50], modal: true, locationRelativeTo: ui.frame, owner: ui.frame, defaultCloseOperation: JFrame.DISPOSE_ON_CLOSE) {
    panel {
        boxLayout(axis: BXL.Y_AXIS)
        panel(alignmentX: 0f) {
            flowLayout(alignment: FL.LEFT)
            label('Choose Template:')
            def selector = comboBox(id: 'type', items: templateList)
            char[] charArray = new char[templateList.collect{ templateName -> templateName.length() }.max()*2]
            java.util.Arrays.fill(charArray, (char) ' ');
            selector.setPrototypeDisplayValue(new String(charArray))
        }
        panel(alignmentX: 0f) {
            flowLayout(alignment: FL.RIGHT)
            button(action: action(name: 'OK', defaultButton: true, mnemonic: 'O',
                    closure: { templateVars.ok = true; dispose() }))
            button(action: action(name: 'Cancel', mnemonic: 'C', closure: { dispose() }))
        }
    }
}

templateDialog.pack()
templateDialog.setLocationRelativeTo(null)
templateDialog.show()

// --- Stage 2: Process Selected Template and Prepare for Form ---

if (templateVars.ok && templateVars.type?.selectedItem) {
    def selectedTemplateText = templateVars.type.selectedItem
    // Find the ORIGINAL template node
    def templateNode = templateHashMap[selectedTemplateText]

    if (!templateNode) {
        c.statusInfo = "Error: Could not find the original template node '${selectedTemplateText}'."
        return
    }

    // --- Find Original Settings Node and Form Items (for reference and potential updates) ---
    def originalSettingsNode = null
    def originalFormsItemsNodes = [] // List of the *original* nodes defining form fields
    def hasSettings = false

    def potentialOriginalSettingsNode = templateNode.children.find { it.plainText.startsWith(SETTINGS_NODE_PREFIX) }
    if (potentialOriginalSettingsNode) {
        hasSettings = true
        originalSettingsNode = potentialOriginalSettingsNode
        originalFormsItemsNodes = originalSettingsNode.children.findAll{ !it.plainText.trim().startsWith(HEADERNODE_SETTING_PREFIX) } // Get originals
        println "Found original settings node. Original form items: ${originalFormsItemsNodes.collect{it.plainText}}"
    } else {
        println "No settings node found in original template '${templateNode.plainText}'."
    }

    // Validating Template items have unique names
    if (originalFormsItemsNodes.size() != originalFormsItemsNodes.collect{ it.plainText }.toSet().size()) {
        JOptionPane.showMessageDialog(ui.frame, "Invalid template: there are Form Items with the same name (core text). Form Items must have unique names.", "Template Picker Error", JOptionPane.ERROR_MESSAGE)
        return
    }
    
    // Collect Header nodes settings in mapping [headerName_lowercase_without_prefix:HeaderSettingsNode]
    def headerNodeSettings = [:]
    if (potentialOriginalSettingsNode) {
        def foundNodesWithHeaderPrefix = potentialOriginalSettingsNode.find{ it.plainText.trim().startsWith(HEADERNODE_SETTING_PREFIX) }
        foundNodesWithHeaderPrefix.each { headerNodeSettings[it.plainText.trim().substring(HEADERNODE_SETTING_PREFIX.length()).toLowerCase().trim()] = it }       
        
        //Validate there are no duplicates for header settings
        if (headerNodeSettings.keySet().size() < foundNodesWithHeaderPrefix.size()) {
                JOptionPane.showMessageDialog(ui.frame, "Invalid template: there are duplicate Header settings nodes. Headers can have at most one Header settings node.", "Header Settings Error", JOptionPane.ERROR_MESSAGE)
                return
        }
    }  
    
    // Copy the template node (not the branch) *after* finding original settings items. Only copy "userAttributes" from original attributes
    def Copy = node.appendChild(templateNode)
    Copy.attributes.getNames().each{ Copy.attributes.removeAll(it)}
    templateNode.attributes.getNames().each{
        if (it.trim().startsWith('*')) Copy[it.trim().substring(1)] = processPlaceholders(templateNode.attributes.get(it) as String, Copy)
    }
   
    // Create the macros header, setting 'Copy' as their 'node' variable (i.e node = Copy)
    header = createHeader(Copy)

    // Find the *copied* settings node (to delete later)
    //def copiedSettingsNode = Copy.children.find { it.plainText.startsWith(SETTINGS_NODE_PREFIX) }

    // --- Stage 3: Show Dynamic Form GUI (if settings items exist) ---
    boolean formOk = true
    def formData = [:] // Map: [Label : UserInputValue]

    if (hasSettings && !originalFormsItemsNodes.isEmpty()) {

        def formBuilder = new SwingBuilder()
        formBuilder.setVariable('formDialog-properties', [:])
        def formVars = formBuilder.variables
        formVars.ok = false

        // Use original nodes to build the form definition
        def formDialog = formBuilder.dialog(title: 'Enter Template Details', id: 'formDialog', modal: true, locationRelativeTo: ui.frame, owner: ui.frame, defaultCloseOperation: JFrame.DISPOSE_ON_CLOSE) {
            panel {
                boxLayout(axis: BXL.Y_AXIS)

                // Dynamically create fields based on *original* item nodes
                originalFormsItemsNodes.eachWithIndex { itemNode, index ->
                    panel(alignmentX: 0f) {
                        flowLayout(alignment: FL.LEFT)
                        label(text: itemNode.plainText + ': ') // Use itemNode text as label

                        // Check for comboType attribute
                        String comboTypeAttr = itemNode.attributes.get("comboType")?.toLowerCase()?.trim()
                        boolean isMultiple = (itemNode.attributes.get("multiple")?.toString()?.toLowerCase() == "true")
                        String defaultText = itemNode.attributes.get("defaultText")?.trim()

                        if ((comboTypeAttr == "open" || comboTypeAttr == "closed") && isMultiple) {
                            button(
                                    text: "Select: ${itemNode.plainText}",
                                    id: "msButton_${index}",
                                    actionPerformed: {
                                        def options = itemNode.children.collect {
                                            it.plainText.startsWith('&' + MASTERLIST_PREFIX) ? masterLists[it.plainText.substring(1)] : it.plainText
                                        }?.flatten()?.minus(null) ?: []

                                        boolean shouldAllowAdd = (comboTypeAttr == "open")

                                        List<String> selectedItemsResult = showMultiSelectDialog(
                                                options,
                                                shouldAllowAdd
                                        )
                                        def msLabelId = "msLabel_${index}"
                                        formVars."${msLabelId}" = selectedItems.join(MULTISELECT_ITEMS_SEPARATOR + " ")
                                    }
                            )
                            label(text: "", id: "msLabel_${index}")
                            formVars."msLabel_${index}" = ""
                        }
                        else if (comboTypeAttr == "open" || comboTypeAttr == "closed") {
                            // --- Create ComboBox ---
                            def options = itemNode.children.collect { it.plainText.startsWith('&' + MASTERLIST_PREFIX) ? masterLists[it.plainText.substring(1)] : it.plainText }?.flatten().minus(null)?: [] // Get child text as options
                            println "Creating ComboBox for '${itemNode.plainText}'. Options: ${options}, Type: ${comboTypeAttr}"
                            comboBox(
                                    id: "combo_${index}", // Specific ID for combos
                                    items: options,
                                    editable: (comboTypeAttr == "open"), // Editable only if "open"
                                    preferredSize: [200, 25] // Give it a decent width
                            )
                        } else {
                            // --- Create TextField (default) ---
                            println "Creating TextField for '${itemNode.plainText}'."
                            def input = textField(
                                    id: "field_${index}", // Standard ID for text fields
                            text: defaultText,
                                     columns: 25
                            )
                            def originalBorder = input.border
                            def errorBorder = BorderFactory.createLineBorder(Color.RED, 2)
                            input.addFocusListener(
                               [focusGained: { e ->             
                                   if (processPlaceholders(e.source.text, Copy).contains(MACROERROR_PREFIX)) e.source.setBorder(errorBorder)
                                   else e.source.setBorder(originalBorder);
                                 },
                               focusLost: {e -> 
                                  def output = processPlaceholders(e.source.text, Copy)
                                  if (output.contains(MACROERROR_PREFIX)) {
                                      e.source.setBorder(errorBorder)
                                      JOptionPane.showMessageDialog(ui.frame, " ERROR: macro errors when processing string:\n\n" + TextUtils.getShortText(output, 300, "...") + "\n\n* If the error is a script error, you may check the stacktrace in Freeplane's log", " ERROR: macro errors found", JOptionPane.ERROR_MESSAGE)
                                  }
                                  else e.source.setBorder(originalBorder);
                                  }] 
                               as FocusListener)   
                                
                            // Case: macro validation when textField contains defaultText
                            if (defaultText != null && !defaultText.trim().isEmpty()) {
                               if (processPlaceholders(input.text, Copy).contains(MACROERROR_PREFIX)) input.setBorder(errorBorder)
                            }
                        }
                    } // End panel for one item
                } // End eachWithIndex

                // OK/Cancel buttons for the form dialog
                panel(alignmentX: 0f) {
                    flowLayout(alignment: FL.RIGHT)
                    button(action: action(name: 'OK', defaultButton: true, mnemonic: 'O',
                            closure: {
                                println "--- Form OK Clicked ---"
                                // Retrieve data and update template (if needed) *before* closing
                                originalFormsItemsNodes.eachWithIndex { itemNode, index ->
                                    def label = itemNode.plainText
                                    String comboTypeAttr = itemNode.attributes.get("comboType")?.toLowerCase()?.trim()
                                    def value = "" // Initialize value for this item

                                    try {
                                        boolean isMultiple = (itemNode.attributes.get("multiple")?.toString()?.toLowerCase() == "true")
                                        if (comboTypeAttr == "open" || comboTypeAttr == "closed") {
                                            def options = itemNode.children.collect { it.plainText.startsWith('&' + MASTERLIST_PREFIX) ? masterLists[it.plainText.substring(1)] : it.plainText }?.flatten().minus(null)?: []

                                            if (isMultiple) {
                                                def msLabelId = "msLabel_${index}"
                                                value = formVars."${msLabelId}" ?: ""
                                                println "Multiselect '${label}': ${value}"

                                                if(comboTypeAttr == "open" && value && value.trim()) {
                                                    def selectedItems = value.split(MULTISELECT_ITEMS_SEPARATOR).collect { it.trim() }
                                                    def currentOptions = itemNode.children.collect { it.plainText }
                                                    selectedItems.each { sel ->
                                                        boolean inMasterList = options.contains(sel)
                                                        if (!currentOptions.contains(sel) && !inMasterList) {
                                                            def newOptionNode = itemNode.createChild(sel)
                                                            newOptionNode.text = sel
                                                        }
                                                    }
                                                }
                                            } else {
                                                def comboId = "combo_${index}"
                                                def cb = formVars."${comboId}"
                                                if (cb instanceof JComboBox) {
                                                    value = cb.selectedItem?.toString() ?: ""
                                                    println "Retrieving from ComboBox '${label}' (ID: ${comboId}) - Value: '${value}'"
                                                    if (comboTypeAttr == "open" && value && value.trim()) {
                                                        def currentOptions = itemNode.children.collect { it.plainText }
                                                        boolean inMasterList = options.contains(value)
                                                        if (!currentOptions.contains(value) && !inMasterList) {
                                                            println "  -> New value detected for open ComboBox '${label}'. Updating template."
                                                            def newOptionNode = itemNode.createChild(value)
                                                            newOptionNode.text = value
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            def fieldId = "field_${index}"
                                            def tf = formVars."${fieldId}"
                                            if (tf instanceof JTextField) {
                                                value = tf.text ?: ""
                                                println "Retrieving from TextField '${label}' (ID: ${fieldId}) - Value: '${value}'"
                                            }
                                        }
                                    } catch (e) {
                                        println "EXCEPTION during retrieval/update for Label: '${label}': ${e.message}"
                                    }
                                    formData[label] = value // Store retrieved value (String)
                                } // End data retrieval loop

                                println "--- Collected formData: ${formData} ---"
                                formVars.ok = true;
                                dispose() // Close the dialog now
                            })) // End OK Closure
                    button(action: action(name: 'Cancel', mnemonic: 'C', closure: { dispose() }))
                } // End button panel
            } // End main form panel
        } // End formDialog definition

        formDialog.pack()
        formDialog.show()
        formOk = formVars.ok

        if (!formOk) {
            c.statusInfo = "Form cancelled by user."
        }

    } // End if(hasSettings && !originalFormsItemsNodes.isEmpty())

    // --- Stage 4: Finalize Node Setup (Apply data to the 'Copy' node) ---

    boolean coreTextSetByForm = false

    // Process the collected form data using original nodes for destinationType info, apply to Copy node
    if (formOk && !formData.isEmpty() && !originalFormsItemsNodes.isEmpty()) {
        println "--- Applying form data to the new node based on 'destinationType' attribute ---"
        originalFormsItemsNodes.each { itemNode -> // Iterate original nodes to get attributes/labels
            def label = itemNode.plainText
            def value = formData[label] // Get user input from map

            // Get destination type from the *original* item node
            def destinationType = itemNode.attributes.get("destinationType")?.toLowerCase()?.trim() ?: "attribute"
            def isMultiSelect =  (itemNode.attributes.get("multiple")?.toString()?.toLowerCase() == "true")
            def userAttributes = itemNode.attributes.getNames().findAll{ name -> name.trim().startsWith('*')}
 
            println "Processing: Label='${label}', Value='${value}', DestinationType='${destinationType}'"

            // Apply value to the 'Copy' node
            switch (destinationType) {
                case "core":
                    if (value != null) { Copy.text = processPlaceholders(value, Copy); coreTextSetByForm = true; println "  Applied to: Core Text" }
                    break
                case "details":
                    Copy.details = processPlaceholders(value ?: "", Copy); println "  Applied to: Details"
                    break
                case "note":
                    Copy.note = processPlaceholders(value ?: "", Copy); println "  Applied to: Note"
                    break
                case ["attribute", "attributes"]:
                    if (label && value != null) { Copy.attributes.set(processPlaceholders(label, Copy), processPlaceholders(value, Copy)); println "  Applied to: Attribute [${label}]" }
                    else { println "  Skipped Attribute: Invalid label or null value." }
                    break
                case ["tag", "tags"]:
                    if (value && value.trim()) {
                        if (isMultiSelect) {
                            selectedItems = value.split(MULTISELECT_ITEMS_SEPARATOR).collect { it.trim() }
                            selectedItems2 = selectedItems.collect { processPlaceholders(it, Copy) }
                            selectedItems2.each { Copy.tags.add(it) }
                        }
                        else Copy.tags.add(processPlaceholders(value, Copy))
                        println "  Applied as: Child Node"
                    }
                    else { println "  Skipped Tag: Value is empty." }
                    break
                case ["childnode", "child"]:
                    if (value != null) {
                        String headerValue = itemNode.attributes.get("header").trim()
                        headerValue = processPlaceholders(headerValue, Copy)
                        def existingHeader =  headerValue? Copy.children.find{ it.plainText.toLowerCase().equals( headerValue.toLowerCase()) }.find() : null
                        def parentNode = headerValue? (existingHeader? existingHeader : Copy.createChild(headerValue)) : Copy
                        
                        // Apply settings to header node when it is created for the first time
                        if (headerValue && !existingHeader && headerNodeSettings[parentNode.plainText.toLowerCase().trim()]) {
                            def headerSettings = headerNodeSettings[parentNode.plainText.toLowerCase().trim()]
                            headerSettings.attributes.getNames().findAll{ name -> name.trim().startsWith('*')}.each{ it -> 
                                parentNode[it.trim().substring(1)] = processPlaceholders(headerSettings.attributes.get(it) as String, Copy) 
                            }
                        }    

                        if (isMultiSelect) {
                            selectedItems = value.split(MULTISELECT_ITEMS_SEPARATOR).collect { it.trim() }
                            selectedItems.each { 
                                def insertedNode = parentNode.createChild(processPlaceholders(it, Copy))
                                userAttributes.each{ attr -> insertedNode[attr.trim().substring(1)] = itemNode[attr] }
                            }
                        }
                        else {
                            def insertedNode = parentNode.createChild(processPlaceholders(value, Copy))
                            userAttributes.each{ attr -> insertedNode[attr.trim().substring(1)] = itemNode[attr] }
                        }
                        println "  Applied as: Child Node"
                    }
                    else { println "  Skipped Child Node: Value is null." }
                    break
                default:
                    println "  WARNING: Unknown destination type '${destinationType}'. Applying as default attribute [${label}]."
                    if (label && value != null) { Copy.attributes.set(label, processPlaceholders(value, Copy)) }
                    break
            }
        }
        c.statusInfo = "Applied template form data."
    } else if (formOk && !hasSettings) {
        c.statusInfo = "Template applied (no form settings found)."
    } else if (formOk) {
        c.statusInfo = "Template applied (form may have been empty or data not processed)."
    }

    // --- Set Standard Properties (if not overridden by form) ---
    def baseName = selectedTemplateText

    if (!coreTextSetByForm) {
        Copy.text = baseName
        println "Setting Node text from template name: '${baseName}'"
    } else {
        println "Node text was set by form item with destinationType 'core'."
    }

    Copy.attributes.set("Template", baseName)
    def today = new Date()
    def formattedDate = today.format('dd-MM-yy')
    Copy.attributes.set("created", formattedDate)
    println "Set standard attributes: Template='${baseName}', created='${formattedDate}'"
   
   // --- Alert user in case Macros produced errors after template execution
   def nodesWithErrors = []
   Copy.find { it.plainText.contains(MACROERROR_PREFIX) }.each {
      nodesWithErrors += it.plainText
      it.getStyle().setTextColor(Color.RED)
   }
   if (!nodesWithErrors.isEmpty()) {
      DefaultListModel<String> model = new DefaultListModel<>()
      nodesWithErrors.each { model.addElement(it) }
      def nodetextJList = new JList<>(model)
      JOptionPane.showMessageDialog(ui.frame, nodetextJList, " ERROR: " + nodesWithErrors.size() + " nodes core text contain macro errors.", JOptionPane.ERROR_MESSAGE)      
   }
   

    // --- Cleanup ---
    /*
    if (copiedSettingsNode) { // Delete the *copied* settings node
        copiedSettingsNode.delete()
        println "Deleted copied settings node."
    }
    */

    if (!formOk){
        c.statusInfo = "Node created, but form input cancelled/aborted"
    }

} else if (templateVars.ok && !templateVars.type?.selectedItem) {
    c.statusInfo = "No template selected."
} else {
    c.statusInfo = "Template selection cancelled."
}


///////////// METHODS //////////////

List<String> showMultiSelectDialog(List<String> listItems, boolean allowAddingItems = true) {
    def swing = new SwingBuilder()

    def checkBoxes = [:]

    def checkboxPanel = swing.panel {
        boxLayout(axis: BoxLayout.Y_AXIS)
        listItems.each { item ->
            checkBoxes[item] = checkBox(text: item)
        }
    }

    def newItemField = null

    def addPanel = swing.panel(layout: new FL(FL.LEFT)) {
        label(text: "New item:")
        newItemField = textField(columns: 15, id: "newItemField")
        button(text: "Add", actionPerformed: {
            def newItem = newItemField.text?.trim()
            if (newItem && !checkBoxes.containsKey(newItem)) {
                def cb = checkBox(text: newItem)
                cb.selected = true
                checkBoxes[newItem] = cb
                checkboxPanel.add(cb)
                checkboxPanel.revalidate()
                checkboxPanel.repaint()
                newItemField.text = ""
            } else {
                JOptionPane.showMessageDialog(ui.frame, "Invalid item, or item already exists!")
            }
        })
    }

    def multiSelectDialog = swing.dialog(
            title: "Select Items",
            modal: true,
            locationRelativeTo: ui.frame,
            owner: ui.frame,
            defaultCloseOperation: JFrame.DISPOSE_ON_CLOSE
    ) {
        panel {
            boxLayout(axis: BoxLayout.Y_AXIS)
            widget(new JScrollPane(checkboxPanel), preferredSize: [200, 350])
            if (allowAddingItems) {
                widget(addPanel)
            }
            panel(layout: new FL(FL.RIGHT)) {
                button(text: "OK", actionPerformed: {
                    if (allowAddingItems && newItemField.text?.trim()) {
                        JOptionPane.showMessageDialog(ui.frame, "You have entered a new item but haven't clicked Add. Please click the Add button and check the item to include it before closing.", "Warning", JOptionPane.WARNING_MESSAGE)
                    } else {
                        dispose()
                    }
                })
                button(text: "Cancel", actionPerformed: { dispose() })
            }
        }
    }

    multiSelectDialog.pack()
    multiSelectDialog.visible = true

    selectedItems = checkBoxes.findAll { key, cb -> cb.selected }.collect { it.key }
    return selectedItems
}

def processPlaceholders (String text, targetNode) {
    if (!text) return text
   
    def p2 = Pattern.compile(/\{\{(.+?)\}\}/)
    def m2 = p2.matcher(text)
    def sb2 = new StringBuffer()
   
    while (m2.find()) {
      String val
      def match = m2.group(1)
      val = evaluateMacro(match, targetNode, header)
      m2.appendReplacement(sb2, Matcher.quoteReplacement(val))
    }
    m2.appendTail(sb2)
   
    return sb2.toString()
}

def evaluateMacro(match, targetNode, header) {
    def expressionMacro = match.startsWith("=") ? match.substring(1) : null
    def fileMacro = match.startsWith("/") ? match.substring(1) : null
    def namedMacro = expressionMacro == null && fileMacro == null ? match : null      
  
    String val
    try {
        String script = header + "\n" + (expressionMacro? expressionMacro : namedMacro? getNamedMacro(namedMacro) : getFileMacro(fileMacro))
        def macroResult = c.script(script, "groovy").executeOn(targetNode) //shell.evaluate(script)
        val = (macroResult != null ? macroResult.toString() : "null")
    }
    catch (Exception e) {
        e.printStackTrace()
        val = "[$MACROERROR_PREFIX caused by " + (namedMacro? "named macro '${namedMacro}'" : expressionMacro? "expression macro '${expressionMacro}'" : fileMacro? "file macro '${fileMacro}'" : "UNDEFINED MACRO TYPE") + ". Message: ${e.message} (${e.class.simpleName})" + (expressionMacro? " caused by expression: ${expressionMacro}":"") + "]"
    }
    return val
}

def getNamedMacro(namedMacro) {
   if (namedMacros[namedMacro] == null) {
      throw new Exception("[Named Macro '${namedMacro}' not found]")
   } else if (namedMacros[namedMacro].trim().isEmpty()) {
      throw new Exception("[Named Macro '${namedMacro}' exists but is empty]")
   }
   return namedMacros[namedMacro]
}

def getFileMacro(scriptName) {
   File scriptFile = scriptDirs.collect { new File(it, scriptName + ".groovy") }.find { it.exists() }
   String rep
   if (scriptFile) {
      try {
         return java.nio.file.Files.readString(scriptFile.toPath())
      }
      catch (Exception e) {
         throw new Exception("[Error executing file '${scriptName}.groovy': ${e.class.simpleName}]")
      }
   } else {
      throw new Exception("[Script file '${scriptName}.groovy' not found]")
   }
}

/**
 * Creates header for all types of macro (i.e {{=expression}}, {{myNamedMacro}}, and  {{/myScriptFileName}})
 */
def createHeader(headerNode) {
    // Must use """ instead of ''' for the interpolation ($) to work
    return header = """
            import org.freeplane.plugin.script.proxy.ScriptUtils
            final c = ScriptUtils.c()
            final node = node.map.node('${headerNode.id}')
            """
}
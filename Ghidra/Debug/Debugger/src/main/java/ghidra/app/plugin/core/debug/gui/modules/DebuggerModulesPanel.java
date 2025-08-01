/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.gui.modules;

import java.util.HashSet;
import java.util.Set;

import javax.swing.event.ListSelectionEvent;

import docking.widgets.table.TableColumnDescriptor;
import ghidra.app.plugin.core.debug.gui.model.*;
import ghidra.app.plugin.core.debug.gui.model.ObjectTableModel.ValueAttribute;
import ghidra.app.plugin.core.debug.gui.model.ObjectTableModel.ValueRow;
import ghidra.app.plugin.core.debug.gui.model.columns.*;
import ghidra.app.plugin.core.debug.service.modules.DebuggerStaticMappingUtils;
import ghidra.debug.api.model.DebuggerObjectActionContext;
import ghidra.docking.settings.Settings;
import ghidra.framework.plugintool.Plugin;
import ghidra.framework.plugintool.ServiceProvider;
import ghidra.program.model.address.*;
import ghidra.trace.model.Trace;
import ghidra.trace.model.modules.TraceModule;
import ghidra.trace.model.modules.TraceSection;
import ghidra.trace.model.target.TraceObject;
import ghidra.trace.model.target.TraceObjectValue;
import ghidra.trace.model.target.path.KeyPath;
import ghidra.trace.model.target.schema.TraceObjectSchema;
import ghidra.trace.model.thread.TraceProcess;

public class DebuggerModulesPanel extends AbstractObjectsTableBasedPanel<TraceModule> {

	private static class ModuleBaseColumn extends AbstractTraceValueObjectAddressColumn {
		public ModuleBaseColumn() {
			super(TraceModule.KEY_RANGE);
		}

		@Override
		public String getColumnName() {
			return "Base";
		}

		@Override
		protected Address fromRange(AddressRange range) {
			return range.getMinAddress();
		}
	}

	private static class ModuleMaxColumn extends AbstractTraceValueObjectAddressColumn {
		public ModuleMaxColumn() {
			super(TraceModule.KEY_RANGE);
		}

		@Override
		public String getColumnName() {
			return "Max";
		}

		@Override
		protected Address fromRange(AddressRange range) {
			return range.getMaxAddress();
		}
	}

	private static class ModuleNameColumn extends TraceValueObjectAttributeColumn<String> {
		public ModuleNameColumn() {
			super(TraceModule.KEY_MODULE_NAME, String.class);
		}

		@Override
		public String getColumnName() {
			return "Name";
		}
	}

	private static class ModuleMappingColumn extends TraceValueKeyColumn {
		@Override
		public String getColumnName() {
			return "Mapping";
		}

		@Override
		public String getValue(ValueRow rowObject, Settings settings, Trace data,
				ServiceProvider serviceProvider) throws IllegalArgumentException {
			if (data == null) {
				return "";
			}
			ValueAttribute<AddressRange> attr =
				rowObject.getAttribute(TraceModule.KEY_RANGE, AddressRange.class);
			if (attr == null) {
				return "";
			}
			AddressRange range = attr.getValue();
			if (range == null) {
				return "";
			}

			// TODO: Cache this? Would flush on:
			//    1. Mapping changes
			//    2. Range/Life changes to this module
			//    3. Snapshot navigation
			return DebuggerStaticMappingUtils.computeMappedFiles(data, rowObject.currentSnap(),
				range);
		}
	}

	private static class ModulePathColumn extends TraceValueKeyColumn {
		@Override
		public String getColumnName() {
			return "Path";
		}

		@Override
		public String getValue(ValueRow rowObject, Settings settings, Trace data,
				ServiceProvider serviceProvider) throws IllegalArgumentException {
			return rowObject.getValue().getCanonicalPath().toString();
		}
	}

	private static class ModuleLengthColumn extends AbstractTraceValueObjectLengthColumn {
		public ModuleLengthColumn() {
			super(TraceModule.KEY_RANGE);
		}

		@Override
		public String getColumnName() {
			return "Length";
		}
	}

	private static class ModuleTableModel extends ObjectTableModel {
		protected ModuleTableModel(Plugin plugin) {
			super(plugin);
		}

		@Override
		protected TableColumnDescriptor<ValueRow> createTableColumnDescriptor() {
			TableColumnDescriptor<ValueRow> descriptor = new TableColumnDescriptor<>();
			descriptor.addHiddenColumn(new ModulePathColumn());
			descriptor.addVisibleColumn(new ModuleBaseColumn(), 1, true);
			descriptor.addVisibleColumn(new ModuleMaxColumn());
			descriptor.addVisibleColumn(new ModuleNameColumn());
			descriptor.addVisibleColumn(new ModuleMappingColumn());
			descriptor.addVisibleColumn(new ModuleLengthColumn());
			return descriptor;
		}
	}

	protected static Set<TraceModule> getSelectedModulesFromContext(
			DebuggerObjectActionContext ctx) {
		Set<TraceModule> result = new HashSet<>();
		for (TraceObjectValue value : ctx.getObjectValues()) {
			if (!value.isObject()) {
				continue;
			}
			TraceObject child = value.getChild();
			TraceModule module = child.queryInterface(TraceModule.class);
			if (module != null) {
				result.add(module);
				continue;
			}
			TraceSection section = child.queryInterface(TraceSection.class);
			if (section != null) {
				result.add(section.getModule());
				continue;
			}
		}
		return result;
	}

	protected static Set<TraceSection> getSelectedSectionsFromContext(
			DebuggerObjectActionContext ctx) {
		Set<TraceSection> result = new HashSet<>();
		for (TraceObjectValue value : ctx.getObjectValues()) {
			if (!value.isObject()) {
				continue;
			}
			TraceObject child = value.getChild();
			TraceModule module = child.queryInterface(TraceModule.class);
			if (module != null) {
				result.addAll(module.getSections(ctx.getSnap()));
				continue;
			}
			TraceSection section = child.queryInterface(TraceSection.class);
			if (section != null) {
				result.add(section);
				continue;
			}
		}
		return result;
	}

	public static AddressSetView getSelectedAddressesFromContext(DebuggerObjectActionContext ctx) {
		AddressSet result = new AddressSet();
		for (TraceObjectValue value : ctx.getObjectValues()) {
			TraceObject child = value.getChild();
			TraceModule module = child.queryInterface(TraceModule.class);
			if (module != null) {
				result.add(module.getRange(ctx.getSnap()));
				continue;
			}
			TraceSection section = child.queryInterface(TraceSection.class);
			if (section != null) {
				result.add(section.getRange(ctx.getSnap()));
				continue;
			}
		}
		return result;
	}

	protected static ModelQuery successorModules(TraceObjectSchema rootSchema, KeyPath path) {
		TraceObjectSchema schema = rootSchema.getSuccessorSchema(path);
		return new ModelQuery(schema.searchFor(TraceModule.class, path, true));
	}

	private final DebuggerModulesProvider provider;

	public DebuggerModulesPanel(DebuggerModulesProvider provider) {
		super(provider.plugin, provider, TraceModule.class);
		this.provider = provider;
	}

	@Override
	protected ObjectTableModel createModel() {
		return new ModuleTableModel(plugin);
	}

	@Override
	protected ModelQuery computeQuery(TraceObject object) {
		TraceObjectSchema rootSchema = object.getRoot().getSchema();
		KeyPath seedPath = object.getCanonicalPath();
		KeyPath processPath = rootSchema.searchForAncestor(TraceProcess.class, seedPath);
		if (processPath != null) {
			ModelQuery result = successorModules(rootSchema, processPath);
			if (!result.isEmpty()) {
				return result;
			}
		}
		KeyPath containerPath = rootSchema.searchForSuitableContainer(TraceModule.class, seedPath);
		if (containerPath != null) {
			ModelQuery result = successorModules(rootSchema, containerPath);
			if (!result.isEmpty()) {
				return result;
			}
		}
		return successorModules(rootSchema, KeyPath.ROOT);
	}

	public void setSelectedModules(Set<TraceModule> sel) {
		setSelected(sel);
	}

	@Override
	public void valueChanged(ListSelectionEvent e) {
		super.valueChanged(e);
		if (e.getValueIsAdjusting()) {
			return;
		}
		provider.modulesPanelContextChanged();
	}
}

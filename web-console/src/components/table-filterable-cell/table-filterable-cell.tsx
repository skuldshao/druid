/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Menu, MenuDivider, MenuItem, Popover } from '@blueprintjs/core';
import type { ReactNode } from 'react';
import React from 'react';
import type { Filter } from 'react-table';

import type { FilterMode } from '../../react-table';
import { addOrUpdateFilter, combineModeAndNeedle, filterModeToIcon } from '../../react-table';
import { Deferred } from '../deferred/deferred';

import './table-filterable-cell.scss';

const FILTER_MODES: FilterMode[] = ['=', '!=', '<', '>='];
const FILTER_MODES_NO_COMPARISONS: FilterMode[] = ['=', '!='];

export interface TableFilterableCellProps {
  field: string;
  value: string;
  filters: Filter[];
  onFiltersChange(filters: Filter[]): void;
  enableComparisons?: boolean;
  children?: ReactNode;
}

export const TableFilterableCell = React.memo(function TableFilterableCell(
  props: TableFilterableCellProps,
) {
  const { field, value, children, filters, enableComparisons, onFiltersChange } = props;

  return (
    <Popover
      className="table-filterable-cell"
      content={
        <Deferred
          content={() => (
            <Menu>
              <MenuDivider title="Filter" />
              {(enableComparisons ? FILTER_MODES : FILTER_MODES_NO_COMPARISONS).map((mode, i) => (
                <MenuItem
                  key={i}
                  icon={filterModeToIcon(mode)}
                  text={value}
                  onClick={() =>
                    onFiltersChange(
                      addOrUpdateFilter(filters, {
                        id: field,
                        value: combineModeAndNeedle(mode, value),
                      }),
                    )
                  }
                />
              ))}
            </Menu>
          )}
        />
      }
    >
      {children ?? value}
    </Popover>
  );
});

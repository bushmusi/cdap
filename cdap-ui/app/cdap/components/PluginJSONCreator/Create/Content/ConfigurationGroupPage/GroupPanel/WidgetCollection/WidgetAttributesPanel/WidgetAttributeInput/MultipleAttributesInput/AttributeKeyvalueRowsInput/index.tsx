/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import * as React from 'react';

import { COMMON_DELIMITER, COMMON_KV_DELIMITER } from 'components/PluginJSONCreator/constants';

import If from 'components/If';
import PluginInput from 'components/PluginJSONCreator/Create/Content/PluginInput';
import { SupportedType } from 'components/PluginJSONCreator/Create/Content/ConfigurationGroupPage/GroupPanel/WidgetCollection/WidgetAttributesPanel/WidgetAttributeInput/MultipleAttributesInput';
import { fromJS } from 'immutable';
import isNil from 'lodash/isNil';
import { isNilOrEmpty } from 'services/helpers';

const AttributeKeyvalueRowsInput = ({
  widgetID,
  field,
  selectedType,
  localWidgetToAttributes,
  setLocalWidgetToAttributes,
}) => {
  const [currentAttributeValues, setCurrentAttributeValues] = React.useState(null);

  // When user switches the selectedType, reset 'currentAttributeValues'.
  // Whenever there is a change in 'localWidgetToAttributes', reset the 'currentAttributeValues'.
  React.useEffect(() => {
    const existingAttributeValues = localWidgetToAttributes.get(widgetID).get(field);
    if (existingAttributeValues) {
      setCurrentAttributeValues(processKeyValueAttributeValues(existingAttributeValues));
    }
  }, [selectedType, localWidgetToAttributes]);

  /*
   * The input fields can have some pre-populated values.
   * For instance, when the user imports a plugin JSON file into the UI,
   * it should parse those pre-populated values of widget attributes
   * and populate the input fields.
   *
   * Process attribute values to pass the values to the input fields.
   * The component 'KeyValueWidget' receives the data of following structure.
   *
   * Example)
   *     "key1;val1,key2;val2"
   */
  function processKeyValueAttributeValues(attributeValues) {
    if (!attributeValues) {
      return '';
    }
    const keyvaluePairs = attributeValues
      .map((keyvalue) => {
        if (!isNil(keyvalue.get('id'))) {
          return [keyvalue.get('id'), keyvalue.get('label')].join(COMMON_KV_DELIMITER);
        } else {
          return [keyvalue.get('value'), keyvalue.get('label')].join(COMMON_KV_DELIMITER);
        }
      })
      .join(COMMON_DELIMITER);
    return keyvaluePairs;
  }

  const onKeyValueAttributeChange = (keyvalue, type: SupportedType) => {
    const keyvaluePairs = keyvalue.split(COMMON_DELIMITER).map((pair) => {
      const [key, value] = pair.split(COMMON_KV_DELIMITER);
      if (type === SupportedType.ValueLabelPair) {
        return { value: key, label: value };
      } else {
        return { id: key, label: value };
      }
    });
    setLocalWidgetToAttributes(
      localWidgetToAttributes.setIn([widgetID, field], fromJS(keyvaluePairs))
    );
  };

  return (
    <If condition={!isNilOrEmpty(currentAttributeValues)}>
      <PluginInput
        widgetType={'keyvalue'}
        value={currentAttributeValues}
        onChange={(keyvalue) => onKeyValueAttributeChange(keyvalue, selectedType)}
        label={field}
        delimiter={COMMON_DELIMITER}
        kvDelimiter={COMMON_KV_DELIMITER}
        keyPlaceholder={selectedType === SupportedType.IDLabelPair ? 'id' : 'value'}
        valuePlaceholder={'label'}
      />
    </If>
  );
};

export default AttributeKeyvalueRowsInput;
